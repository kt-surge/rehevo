param(
  [ValidateRange(1, 10)][int]$Pairs = 1,
  [string]$Model = 'qwen-audio-3.1-tts-flash',
  [string]$Voice = 'longanhuan_v3.1'
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$taskRun = Join-Path $PSScriptRoot ('runs\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$taskPreviousKey = $env:REHEVO_TTS_EXPERIMENT_API_KEY
try {
  if (-not $env:REHEVO_TTS_EXPERIMENT_API_KEY) {
    $env:REHEVO_TTS_EXPERIMENT_API_KEY = $env:AI_BAILIAN_API_KEY
  }
  if (-not $env:REHEVO_TTS_EXPERIMENT_API_KEY) {
    $env:REHEVO_TTS_EXPERIMENT_API_KEY = [Environment]::GetEnvironmentVariable('ALI-API-KEY', 'User')
  }
  if (-not $env:REHEVO_TTS_EXPERIMENT_API_KEY) {
    throw '未找到真实TTS实验凭据。设置 REHEVO_TTS_EXPERIMENT_API_KEY 后复跑。'
  }
  New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
  $taskSources = @('RehevoTtsExperiment.java', 'experiment.init.gradle', 'run-experiment.ps1')
  $taskSourceDirectory = Join-Path $taskRun 'sources'
  New-Item -ItemType Directory -Path $taskSourceDirectory -Force | Out-Null
  $taskHashes = foreach ($taskFile in $taskSources) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $taskFile) -Destination (Join-Path $taskSourceDirectory $taskFile)
    $taskHash = Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $PSScriptRoot $taskFile)
    @{ file = $taskFile; sha256 = $taskHash.Hash.ToLowerInvariant() }
  }
  @{ sources = @($taskHashes); requestedPairs = $Pairs; model = $Model; voice = $Voice; timestamp = (Get-Date).ToUniversalTime().ToString('o') } |
    ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $taskRun 'source-manifest.json') -Encoding UTF8
  Push-Location $taskRoot
  try {
    & .\gradlew.bat :app:rehevoTtsExperiment --no-daemon -I (Join-Path $PSScriptRoot 'experiment.init.gradle') "-PttsExperimentOutput=$taskRun" "-PttsExperimentPairs=$Pairs" "-PttsExperimentModel=$Model" "-PttsExperimentVoice=$Voice" 2>&1 |
      ForEach-Object {
        $taskLine = $_.ToString().Replace($env:REHEVO_TTS_EXPERIMENT_API_KEY, '[REDACTED]') -replace '(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]+', '[REDACTED]'
        Add-Content -LiteralPath (Join-Path $taskRun 'execution.log') -Value $taskLine -Encoding UTF8
        Write-Output $taskLine
      }
    $taskExit = $LASTEXITCODE
  } finally {
    Pop-Location
  }
  Write-Output "实验原始记录：$taskRun"
  exit $taskExit
} finally {
  $env:REHEVO_TTS_EXPERIMENT_API_KEY = $taskPreviousKey
}
