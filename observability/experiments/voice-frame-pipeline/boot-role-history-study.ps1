[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$Inputs,
  [Parameter(Mandatory)][string]$OutputDirectory,
  [ValidateRange(0, 23)][int]$ScheduleOffset = 0,
  [ValidateRange(1, 24)][int]$CallLimit = 2,
  [ValidateSet('roles', 'models')][string]$StudyKind = 'roles',
  [string]$ApprovedPlan = ''
)
$ErrorActionPreference = 'Stop'
$taskProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$taskRuntimeDir = Join-Path $taskProjectRoot 'data\local\rehevo-opt-runtime-20261001'
$taskOutputRoot = [IO.Path]::GetFullPath($OutputDirectory)
$taskInputsPath = (Resolve-Path -LiteralPath $Inputs).Path
$taskApprovedPath = if ($StudyKind -eq 'models') { (Resolve-Path -LiteralPath $ApprovedPlan).Path } else { '' }
$taskAllowedRoot = Join-Path $taskProjectRoot 'observability\experiments\voice-frame-pipeline\runs'
if (-not $taskOutputRoot.StartsWith($taskAllowedRoot + [IO.Path]::DirectorySeparatorChar)) {
  throw 'Output must be inside a new experiment run.'
}
$taskAncestor = [IO.DirectoryInfo]::new($taskOutputRoot)
while ($taskAncestor -and $taskAncestor.FullName.StartsWith($taskAllowedRoot)) {
  if (Test-Path -LiteralPath (Join-Path $taskAncestor.FullName 'artifacts.sha256.json')) {
    throw 'Sealed experiments remain read-only.'
  }
  $taskAncestor = $taskAncestor.Parent
}
if (Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'interview\.guide\.(App|experiments\.VoiceRoleHistoryStudyMain)' }) {
  throw 'Identify and close the existing controlled application before running this isolated study.'
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
& .\gradlew.bat --init-script observability/experiments/voice-frame-pipeline/role-history-study.init.gradle :app:voiceRoleStudy --no-daemon "-PvoiceRoleInputs=$taskInputsPath" "-PvoiceRoleOutput=$taskOutputRoot" "-PvoiceRoleOffset=$ScheduleOffset" "-PvoiceRoleLimit=$CallLimit" "-PvoiceRoleKind=$StudyKind" "-PvoiceRoleApprovedPlan=$taskApprovedPath" *> (Join-Path $taskOutputRoot 'boot.log')
exit $LASTEXITCODE
