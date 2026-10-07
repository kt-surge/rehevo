[CmdletBinding()]
param(
  [string]$BaseUrl = 'http://localhost:8080',
  [int[]]$KnowledgeBaseIds = @(2, 3, 4, 5, 6, 7),
  [switch]$DisableRewrite,
  [ValidateSet('VECTOR', 'HYBRID', 'HYBRID_CONTEXT', 'HYBRID_RERANK')]
  [string]$RetrievalMode = 'VECTOR',
  [string]$DatasetPath = '',
  [string]$OutputDirectory = '',
  [string]$DatasetManifestPath = '',
  [ValidateSet('dev', 'test')]
  [string]$DatasetSplit = '',
  [switch]$RequireReviewed,
  [switch]$ValidateOnly
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

$cases = Get-Content -LiteralPath $DatasetPath -Encoding UTF8 |
  Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
  ForEach-Object { $_ | ConvertFrom-Json }

if ($cases.Count -eq 0) {
  throw '评测集为空'
}

$seenCaseIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$invalidCases = foreach ($case in $cases) {
  $reasons = [System.Collections.Generic.List[string]]::new()
  if ([string]::IsNullOrWhiteSpace($case.id)) {
    $reasons.Add('缺少 id')
  } elseif (-not $seenCaseIds.Add([string]$case.id)) {
    $reasons.Add('id 重复')
  }
  if ([string]::IsNullOrWhiteSpace($case.question) -or $case.question.Trim().Length -lt 2) {
    $reasons.Add('question 不能为空且至少 2 个字符')
  }
  if ($case.answerable -ne $true -and $case.answerable -ne $false) {
    $reasons.Add('answerable 必须为布尔值')
  }
  if ([string]::IsNullOrWhiteSpace($case.referenceAnswer)) {
    $reasons.Add('缺少 referenceAnswer')
  }

  $sourceHashes = @($case.sourceDocumentHashes)
  if ($sourceHashes.Count -eq 0) {
    $reasons.Add('缺少 sourceDocumentHashes')
  }
  foreach ($sourceHash in $sourceHashes) {
    if ([string]$sourceHash -notmatch '^[a-f0-9]{64}$') {
      $reasons.Add("sourceDocumentHashes 含非法 SHA-256：$sourceHash")
      break
    }
  }

  $references = @($case.expectedChunkRefs)
  if ($case.answerable -eq $true -and $references.Count -eq 0) {
    $reasons.Add('可回答题缺少 expectedChunkRefs')
  }
  if ($case.answerable -eq $false -and $references.Count -ne 0) {
    $reasons.Add('不可回答题不得携带 expectedChunkRefs')
  }
  $seenReferences = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
  foreach ($reference in $references) {
    $documentSha256 = [string]$reference.documentSha256
    $chunkIndexText = [string]$reference.chunkIndex
    $chunkIndex = 0
    if ($documentSha256 -notmatch '^[a-f0-9]{64}$') {
      $reasons.Add('expectedChunkRefs 含非法 documentSha256')
      continue
    }
    if (-not [int]::TryParse($chunkIndexText, [ref]$chunkIndex) -or $chunkIndex -lt 0) {
      $reasons.Add('expectedChunkRefs 含非法 chunkIndex')
      continue
    }
    if ($sourceHashes -notcontains $documentSha256) {
      $reasons.Add('expectedChunkRefs 的文档未列入 sourceDocumentHashes')
    }
    if (-not $seenReferences.Add("$documentSha256`:$chunkIndex")) {
      $reasons.Add('expectedChunkRefs 含重复证据键')
    }
  }
  if ($reasons.Count -gt 0) {
    [pscustomobject]@{ id = $case.id; reasons = @($reasons) }
  }
}
if ($invalidCases.Count -gt 0) {
  $samples = @($invalidCases | Select-Object -First 3 | ForEach-Object { "$($_.id)：$($_.reasons -join '；')" }) -join ' | '
  throw "数据集结构校验失败，共 $($invalidCases.Count) 条：$samples"
}

$datasetContract = [ordered]@{
  status = 'unverified'
  datasetSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $DatasetPath).Hash.ToLowerInvariant()
  manifestPath = $null
  split = $null
  reviewed = $false
}

if ($RequireReviewed) {
  $notReviewed = @($cases | Where-Object { $_.reviewStatus -ne 'reviewed' })
  if ($notReviewed.Count -gt 0) {
    $sampleIds = @($notReviewed | Select-Object -First 3 -ExpandProperty id) -join ', '
    throw "数据集存在 $($notReviewed.Count) 条未人工复核样本，不能运行受控基线：$sampleIds"
  }
  $datasetContract.reviewed = $true
}

if (-not [string]::IsNullOrWhiteSpace($DatasetManifestPath)) {
  if ([string]::IsNullOrWhiteSpace($DatasetSplit)) {
    throw '提供 DatasetManifestPath 时必须同时提供 DatasetSplit（dev 或 test）'
  }
  if (-not (Test-Path -LiteralPath $DatasetManifestPath)) {
    throw "未找到数据集清单：$DatasetManifestPath"
  }
  $manifest = Get-Content -LiteralPath $DatasetManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
  $splitContract = $manifest.$DatasetSplit
  if ($null -eq $splitContract) {
    throw "数据集清单不包含 split=$DatasetSplit"
  }
  if ($splitContract.sha256 -ne $datasetContract.datasetSha256) {
    throw "数据集指纹与清单不一致：expected=$($splitContract.sha256), actual=$($datasetContract.datasetSha256)"
  }
  $answerableCount = @($cases | Where-Object { $_.answerable }).Count
  if ($splitContract.cases -ne $cases.Count -or $splitContract.answerable -ne $answerableCount) {
    throw "数据集计数与清单不一致：expected=$($splitContract.cases)/$($splitContract.answerable), actual=$($cases.Count)/$answerableCount"
  }
  $invalidSplit = @($cases | Where-Object { $_.split -ne $DatasetSplit })
  if ($invalidSplit.Count -gt 0) {
    throw "数据集含 $($invalidSplit.Count) 条非 $DatasetSplit split 样本，不能混合运行"
  }
  $datasetContract.status = 'manifest_verified'
  $datasetContract.manifestPath = (Resolve-Path -LiteralPath $DatasetManifestPath).Path
  $datasetContract.split = $DatasetSplit
}

if ($ValidateOnly) {
  [pscustomobject]@{
    datasetPath = (Resolve-Path -LiteralPath $DatasetPath).Path
    totalCases = $cases.Count
    answerableCases = @($cases | Where-Object { $_.answerable }).Count
    datasetContract = [pscustomobject]$datasetContract
  } | ConvertTo-Json -Depth 4
  return
}

$requests = @($cases | ForEach-Object {
  @{ knowledgeBaseIds = @($KnowledgeBaseIds); question = $_.question }
})
$body = @{
  queries = $requests
  rewrite = (-not $DisableRewrite)
  retrievalMode = $RetrievalMode
} | ConvertTo-Json -Depth 6
$httpParameters = @{
  Uri = "$BaseUrl/api/knowledgebase/evaluation/retrieval"
  Method = 'Post'
  ContentType = 'application/json; charset=utf-8'
  Body = [System.Text.Encoding]::UTF8.GetBytes($body)
  TimeoutSec = 600
}
$requestStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
$response = Invoke-RestMethod @httpParameters
$requestStopwatch.Stop()

if (-not $response.success -or $null -eq $response.data.items) {
  throw "评测请求失败：$($response | ConvertTo-Json -Depth 5 -Compress)"
}
if ($response.data.items.Count -ne $cases.Count) {
  throw "响应数量不一致：期望 $($cases.Count)，实际 $($response.data.items.Count)"
}

$perCase = for ($index = 0; $index -lt $cases.Count; $index++) {
  $case = $cases[$index]
  $item = $response.data.items[$index]
  $expected = @($case.expectedChunkRefs | ForEach-Object {
    "$($_.documentSha256):$($_.chunkIndex)"
  })
  $evidence = @($item.evidence)
  $ranks = @()
  for ($rank = 0; $rank -lt $evidence.Count; $rank++) {
    $actual = "$($evidence[$rank].documentSha256):$($evidence[$rank].chunkIndex)"
    if ($expected -contains $actual) {
      $ranks += ($rank + 1)
    }
  }

  $relevantAt5 = @($ranks | Where-Object { $_ -le 5 }).Count
  $recallAt5 = if ($expected.Count -gt 0) { $relevantAt5 / $expected.Count } else { $null }
  $mrrAt10 = if ($ranks.Count -gt 0 -and $ranks[0] -le 10) { 1.0 / $ranks[0] } else { 0.0 }
  $dcgAt10 = 0.0
  foreach ($rank in $ranks | Where-Object { $_ -le 10 }) {
    $dcgAt10 += 1.0 / [Math]::Log($rank + 1, 2)
  }
  $idealCount = [Math]::Min($expected.Count, 10)
  $idcgAt10 = 0.0
  for ($rank = 1; $rank -le $idealCount; $rank++) {
    $idcgAt10 += 1.0 / [Math]::Log($rank + 1, 2)
  }

  [pscustomobject]@{
    id = $case.id
    category = $case.category
    answerable = $case.answerable
    question = $case.question
    retrievalQuery = $item.retrievalQuery
    expectedChunkRefs = $case.expectedChunkRefs
    evidence = $item.evidence
    recallAt5 = $recallAt5
    mrrAt10 = if ($case.answerable) { $mrrAt10 } else { $null }
    ndcgAt10 = if ($case.answerable -and $idcgAt10 -gt 0) { $dcgAt10 / $idcgAt10 } else { $null }
  }
}

$answerable = @($perCase | Where-Object { $_.answerable })
$unanswerable = @($perCase | Where-Object { -not $_.answerable })
$summary = [pscustomobject]@{
  evaluatedAt = (Get-Date).ToString('o')
  gitCommit = $gitHead
  workingTreeDirty = $workingTreeDirty
  workingTreeDiffSha256 = $workingTreeDiffSha256
  knowledgeBaseIds = @($KnowledgeBaseIds)
  rewriteEnabled = (-not $DisableRewrite)
  retrievalMode = $RetrievalMode
  datasetContract = [pscustomobject]$datasetContract
  requestDurationMs = $requestStopwatch.Elapsed.TotalMilliseconds
  averageDurationPerQueryMs = $requestStopwatch.Elapsed.TotalMilliseconds / $cases.Count
  totalCases = $perCase.Count
  answerableCases = $answerable.Count
  unanswerableCases = $unanswerable.Count
  recallAt5 = ($answerable | Measure-Object -Property recallAt5 -Average).Average
  mrrAt10 = ($answerable | Measure-Object -Property mrrAt10 -Average).Average
  ndcgAt10 = ($answerable | Measure-Object -Property ndcgAt10 -Average).Average
  unanswerableEmptyEvidenceRate = ($unanswerable | Where-Object { @($_.evidence).Count -eq 0 }).Count / $unanswerable.Count
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$mode = "$($RetrievalMode.ToLower())-$(if ($DisableRewrite) { 'no-rewrite' } else { 'rewrite' })"
$runPath = Join-Path $OutputDirectory ("retrieval-baseline-$mode-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss'))
[pscustomobject]@{
  summary = $summary
  cases = $perCase
} | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $runPath -Encoding UTF8

$summary | ConvertTo-Json -Depth 4
Write-Host "结果已写入：$runPath"
