param(
  [ValidateRange(1, 50)][int]$Pairs = 10,
  [Parameter(Mandatory)][string]$RunName,
  [ValidatePattern('^[0-9]{8}-r[0-9]+$')][string]$StudyId = '20261004-r4',
  [int]$Seed = 20261004,
  [switch]$Cancellation,
  [switch]$Ordered
)
$ErrorActionPreference = 'Stop'
if ($RunName -notmatch '^[a-z0-9-]+$') { throw 'Use a simple lowercase run name.' }
if ($Ordered -and $Cancellation) { throw 'Choose one study.' }
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$taskRun = Join-Path $taskRoot ('observability\experiments\voice-frame-pipeline\runs\' + $StudyId + '\' + $RunName)
if (Test-Path -LiteralPath $taskRun) { throw 'Run exists; preserve its evidence.' }
$taskPreviousKey = $env:REHEVO_TTS_EXPERIMENT_API_KEY
try {
  if (-not $env:REHEVO_TTS_EXPERIMENT_API_KEY) { $env:REHEVO_TTS_EXPERIMENT_API_KEY = $env:AI_BAILIAN_API_KEY }
  if (-not $env:REHEVO_TTS_EXPERIMENT_API_KEY) {
    $env:REHEVO_TTS_EXPERIMENT_API_KEY = [Environment]::GetEnvironmentVariable('ALI-API-KEY', 'User')
  }
  if (-not $env:REHEVO_TTS_EXPERIMENT_API_KEY) { throw 'Existing experiment credential unavailable.' }
  New-Item -ItemType Directory -Path (Join-Path $taskRun 'sources') | Out-Null
  $taskFiles = @(
    'app/src/main/java/interview/guide/modules/voiceinterview/service/QwenTtsService.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceFrameTtsClient.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceTtsTask.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/service/PcmTtsStreamTask.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/service/OrderedPcmTtsPipeline.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/turn/VoiceTurnResources.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/config/VoiceInterviewProperties.java',
    'app/src/test/java/interview/guide/modules/voiceinterview/service/PcmTtsStreamTaskTest.java',
    'app/build.gradle',
    'observability/experiments/tts-streaming/SdkUsageTap.java',
    'observability/experiments/tts-streaming/RehevoFrameClientExperiment.java',
    'observability/experiments/tts-streaming/RehevoFrameCancellationExperiment.java',
    'observability/experiments/tts-streaming/RehevoOrderedFrameExperiment.java',
    'observability/experiments/tts-streaming/frame-client.init.gradle',
    'observability/experiments/tts-streaming/run-frame-client.ps1'
  )
  $taskManifest = @{}
  for ($taskIndex = 0; $taskIndex -lt $taskFiles.Count; $taskIndex++) {
    $taskPath = Join-Path $taskRoot $taskFiles[$taskIndex]
    $taskName = '{0:d3}.source' -f $taskIndex
    Copy-Item -LiteralPath $taskPath -Destination (Join-Path $taskRun ('sources\' + $taskName))
    $taskManifest[$taskFiles[$taskIndex]] = @{ file=$taskName; sha256=(Get-FileHash -LiteralPath $taskPath -Algorithm SHA256).Hash.ToLowerInvariant() }
  }
  $taskSdkJar = 'C:/Users/yngtao/.gradle/caches/modules-2/files-2.1/com.alibaba/dashscope-sdk-java/2.22.7/b95a7b490469063fd288b4945cad31300fa9f52f/dashscope-sdk-java-2.22.7.jar'
  $taskPlannedCalls = if ($Cancellation) { 3 } elseif ($Ordered) { 5 } else { 2*$Pairs }
  @{ frozenAt=(Get-Date).ToUniversalTime().ToString('o'); sources=$taskManifest; sdkVersion='2.22.7'; sdkSha256=(Get-FileHash -LiteralPath $taskSdkJar -Algorithm SHA256).Hash.ToLowerInvariant(); pairs=$Pairs; seed=$Seed; cancellationStudy=[bool]$Cancellation; orderedStudy=[bool]$Ordered; providerCallsUpperBound=$taskPlannedCalls; experimentObserverOnly=$true } |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $taskRun 'source-manifest.json') -Encoding UTF8
  Push-Location $taskRoot
  try {
    $taskGradleTask = if ($Cancellation) { ':app:frameCancellationExperiment' } elseif ($Ordered) { ':app:orderedFrameExperiment' } else { ':app:frameClientExperiment' }
    & .\gradlew.bat $taskGradleTask --no-daemon -I (Join-Path $PSScriptRoot 'frame-client.init.gradle') "-PframeOutput=$taskRun" "-PframePairs=$Pairs" "-PframeSeed=$Seed" 2>&1 |
      ForEach-Object {
        $taskLine = $_.ToString().Replace($env:REHEVO_TTS_EXPERIMENT_API_KEY, '[REDACTED]') -replace '(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]+', '[REDACTED]'
        Add-Content -LiteralPath (Join-Path $taskRun 'execution.log') -Value $taskLine -Encoding UTF8
        Write-Output $taskLine
      }
    $taskExit = $LASTEXITCODE
  } finally { Pop-Location }
  Write-Output "Saved frame-client experiment: $taskRun"
  exit $taskExit
} finally { $env:REHEVO_TTS_EXPERIMENT_API_KEY = $taskPreviousKey }
