[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)]
  [string]$EvidencePath,
  [switch]$StopAfterVerify
)

$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$taskEvidencePath = [System.IO.Path]::GetFullPath((Join-Path $taskRoot $EvidencePath))
if (-not $taskEvidencePath.StartsWith($taskRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
  throw 'Evidence target must remain in the workspace.'
}
if (Test-Path -LiteralPath $taskEvidencePath) { throw 'Preserve previous runtime proof.' }
$taskOwners = @(Get-NetTCPConnection -LocalPort 18080 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique)
if ($taskOwners.Count -ne 1) { throw 'Expected one isolated runtime owner.' }
$taskOwner = $taskOwners[0]
$taskProcess = Get-CimInstance Win32_Process -Filter "ProcessId = $taskOwner"
if ($taskProcess.Name -ne 'java.exe' -or $taskProcess.CommandLine -notmatch '\sinterview\.guide\.App(\s|$)') {
  throw 'Runtime owner did not match the Java application.'
}
if ($taskProcess.CommandLine -notmatch '\s-cp\s+(?:"([^"]+)"|(\S+))') {
  throw 'Runtime classpath was not found.'
}
$taskJar = if ($matches[1]) { $matches[1] } else { $matches[2] }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$taskZip = [System.IO.Compression.ZipFile]::OpenRead($taskJar)
try {
  $taskReader = [System.IO.StreamReader]::new($taskZip.GetEntry('META-INF/MANIFEST.MF').Open())
  try { $taskManifest = $taskReader.ReadToEnd() -replace '\r?\n ', '' }
  finally { $taskReader.Dispose() }
} finally { $taskZip.Dispose() }
if ($taskManifest -notlike '*D:/IT-Learning/JAVA_base/JavaGuide/programs/interview-guide-master/*') {
  throw 'Classpath manifest did not match this workspace.'
}
$taskProof = @{
  observedAt=[DateTime]::UtcNow.ToString('o'); processId=$taskOwner; port=18080
  processCreatedAt=$taskProcess.CreationDate.ToUniversalTime().ToString('o')
  appMain=$true; manifestWorkspaceMatched=$true; stopAfterVerify=[bool]$StopAfterVerify
  springAi201=($taskManifest -like '*spring-ai-openai-2.0.1.jar*')
  sdk4490=($taskManifest -like '*openai-java-core-4.49.0.jar*')
  health=(Invoke-RestMethod http://127.0.0.1:18080/actuator/health)
}
if (-not $taskProof.springAi201 -or -not $taskProof.sdk4490 -or $taskProof.health.status -ne 'UP') {
  throw 'Expected healthy runtime dependency versions were not verified.'
}
if (-not $StopAfterVerify) {
  $taskProof.classFilesBeforeProcessStart = @('common/evaluation/QuestionEvaluationGuide.class',
    'modules/interview/service/InterviewQuestionService.class', 'modules/interview/skill/InterviewSkillService.class') |
    ForEach-Object {
      $taskClass = Get-Item -LiteralPath (Join-Path $taskRoot "app/build/classes/java/main/interview/guide/$_")
      @{class=$_; sha256=(Get-FileHash -LiteralPath $taskClass.FullName -Algorithm SHA256).Hash;
        lastWriteBeforeStart=($taskClass.LastWriteTimeUtc -le $taskProcess.CreationDate.ToUniversalTime())}
    }
  if ($taskProof.classFilesBeforeProcessStart | Where-Object { -not $_.lastWriteBeforeStart }) {
    throw 'Application started before the current tested classes were built.'
  }
}
$taskProof | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $taskEvidencePath -Encoding UTF8
$taskProof | ConvertTo-Json -Depth 8
if ($StopAfterVerify) { Stop-Process -Id $taskOwner }
