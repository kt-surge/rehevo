[CmdletBinding()]
param(
  [string]$BaseUrl = 'http://localhost:8080',
  [string]$DatasetPath = '',
  [string]$Provider = '',
  [ValidateRange(1, 5)]
  [int]$Repetitions = 3,
  [ValidateRange(1, 1800)]
  [int]$RequestTimeoutSeconds = 600,
  [string]$OutputDirectory = '',
  [string]$DatasetManifestPath = '',
  [switch]$RequireReviewed,
  [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($DatasetPath)) {
  $DatasetPath = Join-Path $PSScriptRoot 'rehevo-evaluation-seed-v1.json'
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
  $OutputDirectory = Join-Path $PSScriptRoot 'runs'
}
if (-not (Test-Path -LiteralPath $DatasetPath)) {
  throw "未找到评分稳定性数据集：$DatasetPath"
}

$request = Get-Content -LiteralPath $DatasetPath -Raw -Encoding UTF8 | ConvertFrom-Json
$cases = @($request.cases)
if ([string]::IsNullOrWhiteSpace($request.datasetId)) {
  throw '评分稳定性数据集缺少 datasetId'
}
if ($cases.Count -eq 0) {
  throw '评分稳定性数据集缺少 cases'
}

$seenCaseIds = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
$invalidCases = foreach ($case in $cases) {
  $reasons = [System.Collections.Generic.List[string]]::new()
  if ([string]::IsNullOrWhiteSpace($case.id)) {
    $reasons.Add('缺少 id')
  } elseif (-not $seenCaseIds.Add([string]$case.id)) {
    $reasons.Add('id 重复')
  }
  if ([string]::IsNullOrWhiteSpace($case.groupId)) { $reasons.Add('缺少 groupId') }
  if ([string]::IsNullOrWhiteSpace($case.question)) { $reasons.Add('缺少 question') }
  if ([string]::IsNullOrWhiteSpace($case.category)) { $reasons.Add('缺少 category') }
  if ([string]::IsNullOrWhiteSpace($case.answer)) { $reasons.Add('缺少 answer') }
  $rank = 0
  if (-not [int]::TryParse([string]$case.expectedRank, [ref]$rank) -or $rank -lt 0) {
    $reasons.Add('expectedRank 必须为非负整数')
  }
  $minimum = 0
  $maximum = 0
  if (-not [int]::TryParse([string]$case.expectedMinScore, [ref]$minimum) -or
      -not [int]::TryParse([string]$case.expectedMaxScore, [ref]$maximum) -or
      $minimum -lt 0 -or $maximum -gt 100 -or $minimum -gt $maximum) {
    $reasons.Add('期望分数区间必须在 0-100 且下界不大于上界')
  }
  if ($reasons.Count -gt 0) {
    [pscustomobject]@{ id = $case.id; reasons = @($reasons) }
  }
}
if ($invalidCases.Count -gt 0) {
  $samples = @($invalidCases | Select-Object -First 3 | ForEach-Object { "$($_.id)：$($_.reasons -join '；')" }) -join ' | '
  throw "评分稳定性数据集结构校验失败，共 $($invalidCases.Count) 条：$samples"
}

$invalidGroups = @($cases | Group-Object groupId | Where-Object {
  $ranks = @($_.Group.expectedRank | ForEach-Object { [int]$_ } | Sort-Object)
  $ranks.Count -ne 3 -or ($ranks -join ',') -ne '0,1,2'
})
if ($invalidGroups.Count -gt 0) {
  $sampleGroups = @($invalidGroups | Select-Object -First 3 -ExpandProperty Name) -join ', '
  throw "评分稳定性数据集分组必须恰有弱/中/强三档（expectedRank=0,1,2）：$sampleGroups"
}

$datasetSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $DatasetPath).Hash.ToLowerInvariant()
$datasetContract = [ordered]@{
  status = 'unverified'
  datasetId = [string]$request.datasetId
  datasetSha256 = $datasetSha256
  cases = $cases.Count
  groups = @($cases.groupId | Sort-Object -Unique).Count
  manifestPath = $null
  reviewed = $false
}
if (-not [string]::IsNullOrWhiteSpace($DatasetManifestPath)) {
  if (-not (Test-Path -LiteralPath $DatasetManifestPath)) {
    throw "未找到评分稳定性数据集清单：$DatasetManifestPath"
  }
  $manifest = Get-Content -LiteralPath $DatasetManifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
  if ($manifest.datasetId -ne $request.datasetId -or $manifest.sha256 -ne $datasetSha256 -or
      $manifest.cases -ne $cases.Count -or $manifest.groups -ne $datasetContract.groups -or
      $manifest.ranksPerGroup -ne 3) {
    throw '评分稳定性数据集与清单不一致，禁止混合运行或覆盖历史结果'
  }
  $datasetContract.status = 'manifest_verified'
  $datasetContract.manifestPath = (Resolve-Path -LiteralPath $DatasetManifestPath).Path
  $datasetContract.reviewed = $manifest.reviewStatus -eq 'reviewed'
}
if ($RequireReviewed -and -not $datasetContract.reviewed) {
  throw '数据集尚未标记为人工复核完成，不能作为受控评分稳定性基线运行'
}
if ($ValidateOnly) {
  [pscustomobject]@{ datasetPath = (Resolve-Path -LiteralPath $DatasetPath).Path; datasetContract = [pscustomobject]$datasetContract } |
    ConvertTo-Json -Depth 5
  return
}

$request.repetitions = $Repetitions
if (-not [string]::IsNullOrWhiteSpace($Provider)) {
  $request.llmProvider = $Provider
}

$body = $request | ConvertTo-Json -Depth 8
$startedAt = Get-Date
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$runPath = Join-Path $OutputDirectory (
  "evaluation-stability-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss')
)

try {
  $response = Invoke-RestMethod `
    -Uri "$BaseUrl/api/interview/evaluation/benchmark" `
    -Method Post `
    -ContentType 'application/json; charset=utf-8' `
    -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) `
    -TimeoutSec $RequestTimeoutSeconds
} catch {
  $failure = [pscustomobject]@{
    status = 'REQUEST_FAILED'
    startedAt = $startedAt.ToString('o')
    finishedAt = (Get-Date).ToString('o')
    elapsedSeconds = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 3)
    requestTimeoutSeconds = $RequestTimeoutSeconds
    baseUrl = $BaseUrl
    datasetPath = (Resolve-Path -LiteralPath $DatasetPath).Path
    datasetContract = [pscustomobject]$datasetContract
    errorType = $_.Exception.GetType().FullName
    errorMessage = $_.Exception.Message
  }
  $failure | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $runPath -Encoding UTF8
  Write-Host "失败证据已写入：$runPath"
  throw
}

if (-not $response.success -or $null -eq $response.data.summary) {
  $failure = [pscustomobject]@{
    status = 'SERVICE_REJECTED'
    startedAt = $startedAt.ToString('o')
    finishedAt = (Get-Date).ToString('o')
    elapsedSeconds = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 3)
    requestTimeoutSeconds = $RequestTimeoutSeconds
    baseUrl = $BaseUrl
    datasetPath = (Resolve-Path -LiteralPath $DatasetPath).Path
    datasetContract = [pscustomobject]$datasetContract
    response = $response
  }
  $failure | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $runPath -Encoding UTF8
  throw "评分稳定性评测失败：$($response | ConvertTo-Json -Depth 5 -Compress)"
}

$result = [pscustomobject]@{
  status = 'SUCCESS'
  startedAt = $startedAt.ToString('o')
  finishedAt = (Get-Date).ToString('o')
  elapsedSeconds = [math]::Round(((Get-Date) - $startedAt).TotalSeconds, 3)
  requestTimeoutSeconds = $RequestTimeoutSeconds
  gitCommit = (git -C (Join-Path $PSScriptRoot '..\..\..') rev-parse HEAD).Trim()
  baseUrl = $BaseUrl
  datasetPath = (Resolve-Path -LiteralPath $DatasetPath).Path
  datasetContract = [pscustomobject]$datasetContract
  result = $response.data
}
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $runPath -Encoding UTF8

$response.data.summary | ConvertTo-Json -Depth 4
Write-Host "结果已写入：$runPath"
