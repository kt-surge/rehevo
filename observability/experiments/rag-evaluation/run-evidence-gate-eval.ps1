[CmdletBinding()]
param(
  [string]$BaseUrl = 'http://localhost:8080',
  [int[]]$KnowledgeBaseIds = @(2, 3, 4, 5, 6, 7),
  [ValidateSet('VECTOR', 'HYBRID', 'HYBRID_CONTEXT', 'HYBRID_RERANK')]
  [string]$RetrievalMode = 'HYBRID',
  [switch]$DisableRewrite,
  [string]$DatasetPath = '',
  [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$gitHead = (git -C $projectRoot rev-parse HEAD).Trim()
$workingTreeDirty = -not [string]::IsNullOrWhiteSpace((git -C $projectRoot status --porcelain))
$workingTreeDiff = git -C $projectRoot diff --no-ext-diff HEAD | Out-String
$untrackedFingerprints = @(git -C $projectRoot ls-files --others --exclude-standard | ForEach-Object {
  $path = $_
  $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $projectRoot $path)).Hash.ToLowerInvariant()
  "$path=$hash"
}) -join "`n"
$workingTreeDiffSha256 = [Convert]::ToHexString(
  [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes("$workingTreeDiff`n$untrackedFingerprints"))
).ToLowerInvariant()

if ([string]::IsNullOrWhiteSpace($DatasetPath)) {
  $DatasetPath = Join-Path $PSScriptRoot 'rehevo-gold-v1.jsonl'
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
  $OutputDirectory = Join-Path $PSScriptRoot 'runs'
}
if (-not (Test-Path -LiteralPath $DatasetPath)) {
  throw "未找到评测集：$DatasetPath"
}

$cases = @(Get-Content -LiteralPath $DatasetPath -Encoding UTF8 |
  Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
  ForEach-Object { $_ | ConvertFrom-Json })
if ($cases.Count -eq 0) {
  throw '评测集为空'
}

$body = @{
  queries = @($cases | ForEach-Object { @{ knowledgeBaseIds = @($KnowledgeBaseIds); question = $_.question } })
  rewrite = (-not $DisableRewrite)
  retrievalMode = $RetrievalMode
} | ConvertTo-Json -Depth 6

$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$response = Invoke-RestMethod -Uri "$BaseUrl/api/knowledgebase/evaluation/retrieval" -Method Post `
  -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 600
$stopwatch.Stop()

if (-not $response.success -or $null -eq $response.data.items -or $response.data.items.Count -ne $cases.Count) {
  throw '证据门评测响应无效或数量不一致'
}

$perCase = for ($index = 0; $index -lt $cases.Count; $index++) {
  $case = $cases[$index]
  $item = $response.data.items[$index]
  if ($null -eq $item.evidenceAssessment) {
    throw '服务端证据门处于 OFF；请使用 OBSERVE 模式运行评测。'
  }
  [pscustomobject]@{
    id = $case.id
    category = $case.category
    expectedAnswerable = [bool]$case.answerable
    predictedAnswerable = [bool]$item.evidenceAssessment.sufficient
    reason = $item.evidenceAssessment.reason
    candidateCount = $item.evidenceAssessment.candidateCount
    numericConstraintDetected = $item.evidenceAssessment.numericConstraintDetected
    numericEvidencePresent = $item.evidenceAssessment.numericEvidencePresent
    question = $case.question
  }
}

$tp = @($perCase | Where-Object { $_.expectedAnswerable -and $_.predictedAnswerable }).Count
$fp = @($perCase | Where-Object { -not $_.expectedAnswerable -and $_.predictedAnswerable }).Count
$fn = @($perCase | Where-Object { $_.expectedAnswerable -and -not $_.predictedAnswerable }).Count
$tn = @($perCase | Where-Object { -not $_.expectedAnswerable -and -not $_.predictedAnswerable }).Count
$precision = if (($tp + $fp) -gt 0) { $tp / ($tp + $fp) } else { 0.0 }
$recall = if (($tp + $fn) -gt 0) { $tp / ($tp + $fn) } else { 0.0 }
$f1 = if (($precision + $recall) -gt 0) { 2 * $precision * $recall / ($precision + $recall) } else { 0.0 }
$abstainPrecision = if (($tn + $fn) -gt 0) { $tn / ($tn + $fn) } else { 0.0 }
$abstainRecall = if (($tn + $fp) -gt 0) { $tn / ($tn + $fp) } else { 0.0 }

$summary = [pscustomobject]@{
  evaluatedAt = (Get-Date).ToString('o')
  gitCommit = $gitHead
  workingTreeDirty = $workingTreeDirty
  workingTreeDiffSha256 = $workingTreeDiffSha256
  dataset = (Split-Path -Leaf $DatasetPath)
  knowledgeBaseIds = @($KnowledgeBaseIds)
  retrievalMode = $RetrievalMode
  rewriteEnabled = (-not $DisableRewrite)
  requestDurationMs = $stopwatch.Elapsed.TotalMilliseconds
  totalCases = $perCase.Count
  truePositive = $tp
  falsePositive = $fp
  falseNegative = $fn
  trueNegative = $tn
  answerablePrecision = $precision
  answerableRecall = $recall
  answerableF1 = $f1
  abstainPrecision = $abstainPrecision
  abstainRecall = $abstainRecall
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$runPath = Join-Path $OutputDirectory ("evidence-gate-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
[pscustomobject]@{ summary = $summary; cases = $perCase } | ConvertTo-Json -Depth 8 |
  Set-Content -LiteralPath $runPath -Encoding UTF8

$summary | ConvertTo-Json -Depth 5
Write-Host "结果已写入：$runPath"
