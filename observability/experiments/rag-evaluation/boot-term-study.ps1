[CmdletBinding()]
param([Parameter(Mandatory)][string]$Inputs, [Parameter(Mandatory)][string]$OutputDirectory,
  [ValidateRange(0,11)][int]$ScheduleOffset=0, [ValidateRange(1,12)][int]$CallLimit=4)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$taskAllowed=Join-Path $taskRoot 'observability\experiments\rag-evaluation\runs'
$taskOutput=[IO.Path]::GetFullPath($OutputDirectory)
if (-not $taskOutput.StartsWith($taskAllowed+[IO.Path]::DirectorySeparatorChar)) { throw 'Output outside experiment scope' }
$taskAncestor=[IO.DirectoryInfo]::new($taskOutput)
while ($taskAncestor -and $taskAncestor.FullName.StartsWith($taskAllowed)) {
  if (Test-Path -LiteralPath (Join-Path $taskAncestor.FullName 'artifacts.sha256.json')) { throw 'Preserve sealed run' }
  $taskAncestor=$taskAncestor.Parent
}
if (Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match 'interview\.guide\.(App|experiments\.RagTermBoundaryStudyMain)' }) { throw 'Controlled App must be stopped' }
$taskInputs=(Resolve-Path -LiteralPath $Inputs).Path
New-Item -ItemType Directory -Path $taskOutput -ErrorAction Stop | Out-Null
$taskRuntime=Join-Path $taskRoot 'data\local\rehevo-opt-runtime-20261001'
foreach ($line in Get-Content -LiteralPath (Join-Path $taskRuntime '.env') -Encoding UTF8) {
  if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { [Environment]::SetEnvironmentVariable($matches[1],$matches[2],'Process') }
}
$env:APP_AI_CONFIG_YAML_PATH=Join-Path $taskRuntime 'providers.yml'
$env:APP_AI_CONFIG_ENV_PATH=Join-Path $taskRuntime 'providers.env'
$env:JAVA_HOME='D:\JAVA\JDK21'
Set-Location -LiteralPath $taskRoot
& .\gradlew.bat --init-script observability/experiments/rag-evaluation/term-study.init.gradle :app:termStudy --no-daemon "-PtermInputs=$taskInputs" "-PtermOutput=$taskOutput" "-PtermOffset=$ScheduleOffset" "-PtermLimit=$CallLimit" *> (Join-Path $taskOutput 'boot.log')
exit $LASTEXITCODE
