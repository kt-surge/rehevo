[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^[a-z][a-z0-9-]{0,40}$')]
  [string]$Label,
  [ValidatePattern('^[a-z][a-z0-9-]{0,60}$')]
  [string]$Experiment = 'status-delete-20261001',
  [ValidatePattern('^[a-zA-Z][a-zA-Z0-9]*\*?$')]
  [string]$TestMethod,
  [ValidatePattern('^[A-Za-z][A-Za-z0-9]*IntegrationTest$')]
  [string]$TestClass = 'VectorizeFaultIntegrationTest',
  [string]$SourceRoot
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$taskWorkspaceRoot = $taskRoot
if ($SourceRoot) {
  $taskSourceRoot = (Resolve-Path -LiteralPath $SourceRoot).Path
  $taskAllowedRoot = Join-Path $taskWorkspaceRoot 'data\local\durable-delivery-baseline-20261002'
  if (-not $taskSourceRoot.Equals($taskAllowedRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Only the verified frozen baseline source directory is allowed.'
  }
  $taskRoot = $taskSourceRoot
}
$taskRun = Join-Path (Join-Path $PSScriptRoot 'runs') $Experiment
New-Item -ItemType Directory -Path $taskRun -Force | Out-Null
$taskLog = Join-Path $taskRun "$Label.log"
$taskXml = Join-Path $taskRun "$Label.xml"
if ((Test-Path -LiteralPath $taskLog) -or (Test-Path -LiteralPath $taskXml)) {
  throw 'Fault evidence exists; use a new explicit label.'
}
if (Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue) {
  throw 'Stop the confirmed experiment backend before using its isolated fault database.'
}
$taskEnv = Join-Path $taskWorkspaceRoot 'data\local\rehevo-opt-runtime-20261001\.env'
foreach ($taskLine in Get-Content -LiteralPath $taskEnv -Encoding UTF8) {
  if ($taskLine -match '^POSTGRES_PASSWORD=(.*)$') {
    $env:REHEVO_FAULT_DB_PASSWORD = $matches[1]
  }
}
if (-not $env:REHEVO_FAULT_DB_PASSWORD) { throw 'Isolated DB credential not configured.' }
$env:JAVA_HOME = 'D:\JAVA\JDK21'
$env:REHEVO_ASYNC_FAULT = 'isolated-20261001'
if ($TestClass -in @('VectorTaskDeliveryRecoveryIntegrationTest', 'VectorTaskDurableContractIntegrationTest', 'VectorTaskNotificationIntegrationTest', 'VectorExecutionRaceIntegrationTest', 'VectorExecutionContractIntegrationTest', 'VectorExecutionFailureIntegrationTest')) {
  $env:REHEVO_FAULT_DURABLE = 'true'
}
Set-Location -LiteralPath $taskRoot
$taskSources = @(
  'app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamConsumer.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/repository/VectorRepository.java',
  'app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorizeFaultIntegrationTest.java',
  'app/src/main/java/interview/guide/modules/interview/listener/EvaluateStreamConsumer.java',
  'app/src/main/java/interview/guide/modules/resume/listener/AnalyzeStreamConsumer.java',
  'app/src/main/java/interview/guide/modules/voiceinterview/listener/VoiceEvaluateStreamConsumer.java',
  'app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java',
  'app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorizeCrashWorker.java'
)
$taskSources += "app/src/test/java/interview/guide/modules/knowledgebase/listener/$TestClass.java"
if ($TestClass -eq 'VectorTaskDeliveryRecoveryIntegrationTest') {
  $taskSources += 'app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorTaskEnqueueCrashWorker.java'
}
if ($TestClass -eq 'VectorTaskNotificationIntegrationTest') {
  $taskSources += 'app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorTaskNotificationCrashWorker.java'
}
if ($TestClass -eq 'VectorExecutionFailureIntegrationTest') {
  $taskSources += 'app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorExecutionCrashWorker.java'
}
$taskSources += @(
  'app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamProducer.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorTaskService.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBasePersistenceService.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseUploadService.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/model/KnowledgeBaseEntity.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/repository/KnowledgeBaseRepository.java',
  'app/src/main/java/interview/guide/common/constant/AsyncTaskStreamConstants.java'
)
$taskOptionalSources = @(
  'app/src/main/java/interview/guide/common/async/AbstractStreamProducer.java',
  'app/src/main/java/interview/guide/common/config/VectorTaskRecoveryProperties.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/model/VectorTaskInput.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/model/VectorTaskEntity.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/model/VectorTaskDeliveryDTO.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/repository/VectorTaskRepository.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/service/VectorTaskRecoveryService.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/model/VectorTaskExecutionDTO.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/model/VectorExecutionClaimDTO.java',
  'app/src/main/java/interview/guide/modules/knowledgebase/service/VectorTaskExecutionHeartbeat.java',
  'app/src/main/java/interview/guide/common/config/VectorTaskExecutionConfiguration.java',
  'app/src/main/java/interview/guide/common/metrics/ApplicationMetrics.java',
  'app/src/main/resources/application.yml'
)
foreach ($taskOptional in $taskOptionalSources) {
  if (Test-Path -LiteralPath (Join-Path $taskRoot $taskOptional)) { $taskSources += $taskOptional }
}
$taskHashes = [ordered]@{}
foreach ($taskSource in $taskSources) {
  $taskHashes[$taskSource] = (Get-FileHash -LiteralPath (Join-Path $taskRoot $taskSource) -Algorithm SHA256).Hash.ToLowerInvariant()
}
$taskHashes | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRun "$Label-source-hashes.json") -Encoding UTF8
$taskStarted = [DateTime]::UtcNow
$taskTestFilter = "interview.guide.modules.knowledgebase.listener.$TestClass"
if ($TestMethod) { $taskTestFilter += ".$TestMethod" }
$env:REHEVO_FAULT_ARTIFACTS = Join-Path $taskRun "$Label-child"
$env:REHEVO_FAULT_CLASSPATH_FILE = [System.IO.Path]::GetTempFileName()
$taskPreviousPreference = $ErrorActionPreference
try {
  # Windows PowerShell 5 treats compiler stderr warnings as errors; actual native exit is authoritative.
  $ErrorActionPreference = 'Continue'
  & .\gradlew.bat -I (Join-Path $PSScriptRoot 'fault-classpath.init.gradle') :app:integrationTest --tests $taskTestFilter --no-daemon --rerun-tasks *> $taskLog
  $taskExit = $LASTEXITCODE
} finally { $ErrorActionPreference = $taskPreviousPreference }
$taskResult = Join-Path $taskRoot "app\build\test-results\integrationTest\TEST-interview.guide.modules.knowledgebase.listener.$TestClass.xml"
$taskCaptured = $false
if ((Test-Path -LiteralPath $taskResult) -and ((Get-Item -LiteralPath $taskResult).LastWriteTimeUtc -ge $taskStarted)) {
  Copy-Item -LiteralPath $taskResult -Destination $taskXml
  $taskCaptured = $true
}
@{ startedAtUtc = $taskStarted.ToString('o'); exitCode = $taskExit; freshIntegrationXmlCaptured = $taskCaptured;
   testFilter = $taskTestFilter; sourceRoot = $taskRoot; durableExperimentRequested = ($env:REHEVO_FAULT_DURABLE -eq 'true') } |
  ConvertTo-Json | Set-Content -LiteralPath (Join-Path $taskRun "$Label-invocation.json") -Encoding UTF8
Remove-Item Env:\REHEVO_FAULT_DB_PASSWORD
Remove-Item Env:\REHEVO_FAULT_ARTIFACTS
Remove-Item -LiteralPath $env:REHEVO_FAULT_CLASSPATH_FILE
Remove-Item Env:\REHEVO_FAULT_CLASSPATH_FILE
Remove-Item Env:\REHEVO_FAULT_DURABLE -ErrorAction SilentlyContinue
exit $taskExit
