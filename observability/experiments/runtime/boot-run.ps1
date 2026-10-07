[CmdletBinding()]
param(
  [ValidateSet('TOKEN', 'STRUCTURED')]
  [string]$ChunkingMode = 'TOKEN',
  [ValidateRange(64, 2048)]
  [int]$ChunkingMaxTokens = 800,
  [ValidateSet('false', 'true')]
  [string]$DurableVectorTasks = 'false',
  [string]$CorsAllowedOrigins = '',
  [ValidateRange(0, 6000)]
  [int]$AsrSilenceMs = 0,
  [switch]$AsrDiagnostics
)

$ErrorActionPreference = 'Stop'
$taskProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$taskRuntimeDir = Join-Path $taskProjectRoot 'data\local\rehevo-opt-runtime-20261001'
$taskEnvFile = Join-Path $taskRuntimeDir '.env'
if (-not (Test-Path -LiteralPath $taskEnvFile)) {
  throw 'Prepare the isolated runtime first.'
}
foreach ($line in Get-Content -LiteralPath $taskEnvFile -Encoding UTF8) {
  if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
  }
}
$env:APP_AI_CONFIG_YAML_PATH = Join-Path $taskRuntimeDir 'providers.yml'
$env:APP_AI_CONFIG_ENV_PATH = Join-Path $taskRuntimeDir 'providers.env'
$env:JAVA_HOME = 'D:\JAVA\JDK21'
Set-Location -LiteralPath $taskProjectRoot
# Controlled experiments: avoid automatic opening-audio synthesis during startup.
$taskArguments = "--args=--server.address=127.0.0.1 --server.port=18080 --app.voice-interview.opening.warmup-enabled=false --app.ai.rag.chunking.mode=$ChunkingMode --app.ai.rag.chunking.max-tokens=$ChunkingMaxTokens --app.ai.rag.vector-task.durable-enabled=$DurableVectorTasks"
if ($CorsAllowedOrigins) {
  if ($CorsAllowedOrigins -notmatch '^http://(127\.0\.0\.1|localhost):[0-9]+$') {
    throw 'Controlled browser origin must be one explicit localhost origin.'
  }
  $taskArguments += " --app.cors.allowed-origins=$CorsAllowedOrigins"
}
if ($AsrSilenceMs -ne 0) {
  if ($AsrSilenceMs -lt 200) { throw 'Streaming silence threshold must be at least 200ms.' }
  $taskArguments += " --app.voice-interview.qwen.asr.turn-detection-silence-duration-ms=$AsrSilenceMs"
}
if ($AsrDiagnostics) {
  $taskArguments += ' --logging.level.interview.guide.modules.voiceinterview.handler.VoiceInterviewWebSocketHandler=DEBUG'
}
& .\gradlew.bat :app:bootRun --no-daemon $taskArguments *> (Join-Path $taskRuntimeDir 'boot-run.log')
exit $LASTEXITCODE
