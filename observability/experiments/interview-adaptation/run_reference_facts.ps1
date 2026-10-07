[CmdletBinding()]
param(
  [ValidateSet('baseline', 'candidate')][string]$Arm,
  [ValidateSet('boot-config', 'spring-proxy', 'mysql-order', 'mq-commit')][string]$Case
)

$ErrorActionPreference = 'Stop'
$taskProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$taskRuntimeDir = Join-Path $taskProjectRoot 'data\local\rehevo-opt-runtime-20261001'
$taskRun = Join-Path $taskProjectRoot "observability\experiments\voice-frame-pipeline\runs\reference-facts-live-$Case-$Arm-20261006-r1"
$taskSources = Join-Path $taskProjectRoot "observability\experiments\voice-frame-pipeline\runs\reference-facts-source-$Arm-20261006-r1"
if (-not (Test-Path -LiteralPath (Join-Path $taskRun 'scope-before.json'))) {
  throw 'Prepare and verify the frozen run first.'
}
if ((Test-Path -LiteralPath (Join-Path $taskRun 'driver.log')) -or
    (Test-Path -LiteralPath (Join-Path $taskRun 'artifacts.sha256.json'))) {
  throw 'Existing or sealed attempts must not be overwritten.'
}
foreach ($line in Get-Content -LiteralPath (Join-Path $taskRuntimeDir '.env') -Encoding UTF8) {
  if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
  }
}
$env:APP_AI_CONFIG_YAML_PATH = Join-Path $taskRuntimeDir 'providers.yml'
$env:APP_AI_CONFIG_ENV_PATH = Join-Path $taskRuntimeDir 'providers.env'
$env:JAVA_HOME = 'D:\JAVA\JDK21'
Set-Location -LiteralPath $taskProjectRoot
$taskArguments = @(
  '--init-script', 'observability/experiments/interview-adaptation/reference-facts.init.gradle',
  ':app:referenceFactsStudy', '--no-daemon',
  "-PrefsInput=$(Join-Path $taskRun 'input.json')",
  "-PrefsOutput=$taskRun",
  "-PrefsResources=$(Join-Path $taskSources 'references')",
  "-PrefsArm=$Arm"
)
& .\gradlew.bat @taskArguments *> (Join-Path $taskRun 'driver.log')
exit $LASTEXITCODE
