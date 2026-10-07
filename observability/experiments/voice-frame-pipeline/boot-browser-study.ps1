[CmdletBinding()]
param(
  [ValidateSet('whole', 'frames')]
  [string]$Arm,
  [ValidateSet(18080, 18081)]
  [int]$Port,
  [ValidateSet('false', 'true')]
  [string]$PreloadSkill = 'false',
  [ValidateSet('current', 'baseline-question')]
  [string]$QuestionVariant = 'current',
  [Parameter(Mandatory)]
  [string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$taskProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$taskRuntimeDir = Join-Path $taskProjectRoot 'data\local\rehevo-opt-runtime-20261001'
$taskOutputRoot = [IO.Path]::GetFullPath($OutputDirectory)
$taskAllowedRoot = Join-Path $taskProjectRoot 'observability\experiments\voice-frame-pipeline\runs'
if (-not $taskOutputRoot.StartsWith($taskAllowedRoot + [IO.Path]::DirectorySeparatorChar)) {
  throw 'Study output must be inside the new experiment run.'
}
$taskAncestor = [IO.DirectoryInfo]::new($taskOutputRoot)
while ($taskAncestor -and $taskAncestor.FullName.StartsWith($taskAllowedRoot)) {
  if (Test-Path -LiteralPath (Join-Path $taskAncestor.FullName 'artifacts.sha256.json')) {
    throw 'A sealed experiment directory must remain read-only.'
  }
  $taskAncestor = $taskAncestor.Parent
}
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) {
  throw 'The requested study port already has a listener; identify it before stopping anything.'
}
New-Item -ItemType Directory -Path $taskOutputRoot -ErrorAction Stop | Out-Null
foreach ($line in Get-Content -LiteralPath (Join-Path $taskRuntimeDir '.env') -Encoding UTF8) {
  if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
  }
}
$env:APP_AI_CONFIG_YAML_PATH = Join-Path $taskRuntimeDir 'providers-voice-browser.yml'
$env:APP_AI_CONFIG_ENV_PATH = Join-Path $taskRuntimeDir 'providers.env'
$env:JAVA_HOME = 'D:\JAVA\JDK21'
Set-Location -LiteralPath $taskProjectRoot
$taskFrames = if ($Arm -eq 'frames') { 'true' } else { 'false' }
$taskUsageOutput = Join-Path $taskOutputRoot 'tts-usage.jsonl'
& .\gradlew.bat --init-script observability/experiments/voice-frame-pipeline/browser-study.init.gradle :app:voiceBrowserStudy --no-daemon "-PvoiceStudyPort=$Port" "-PvoiceStudyFrames=$taskFrames" "-PvoiceStudyPreload=$PreloadSkill" "-PvoiceStudyQuestionVariant=$QuestionVariant" "-PvoiceStudyOutput=$taskUsageOutput" *> (Join-Path $taskOutputRoot 'boot.log')
exit $LASTEXITCODE
