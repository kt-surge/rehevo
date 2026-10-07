[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [long[]]$KnowledgeBaseIds,

    [ValidateRange(2, 100)]
    [int]$MinimumChunksPerDocument = 3,

    [string]$ContainerName = "rehevo-postgres",

    [string]$DatabaseName = "rehevo",

    [string]$DatabaseUser = "postgres",

    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"

function Invoke-ReadOnlySql {
    param([Parameter(Mandatory = $true)][string]$Sql)

    $rows = & docker exec $ContainerName psql -U $DatabaseUser -d $DatabaseName -At -F "`t" -c $Sql
    if ($LASTEXITCODE -ne 0) {
        throw "无法读取本地 PostgreSQL 容器 '$ContainerName'；请确认 docker compose 已启动且容器名正确。"
    }
    return @($rows | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

$uniqueIds = @($KnowledgeBaseIds | Sort-Object -Unique)
$idList = $uniqueIds -join ","
$sql = @"
SELECT
  kb.id,
  kb.file_hash,
  kb.original_filename,
  kb.vector_status,
  kb.chunk_count,
  COUNT(v.id) AS stored_chunks,
  COUNT(DISTINCT (v.metadata->>'chunk_index')::int) AS distinct_chunk_indexes,
  COALESCE(MIN((v.metadata->>'chunk_index')::int), -1) AS first_chunk_index,
  COALESCE(MAX((v.metadata->>'chunk_index')::int), -1) AS last_chunk_index
FROM knowledge_bases kb
LEFT JOIN vector_store v ON v.metadata->>'kb_id' = kb.id::text
WHERE kb.id IN ($idList)
GROUP BY kb.id, kb.file_hash, kb.original_filename, kb.vector_status, kb.chunk_count
ORDER BY kb.id;
"@

$rows = Invoke-ReadOnlySql -Sql $sql
$documents = @($rows | ForEach-Object {
    $fields = $_ -split "`t", 9
    if ($fields.Count -ne 9) {
        throw "无法解析 PostgreSQL 返回行：$_"
    }
    [pscustomobject]@{
        knowledgeBaseId = [long]$fields[0]
        documentSha256 = $fields[1]
        originalFilename = $fields[2]
        vectorStatus = $fields[3]
        recordedChunkCount = [int]$fields[4]
        storedChunks = [int]$fields[5]
        distinctChunkIndexes = [int]$fields[6]
        firstChunkIndex = [int]$fields[7]
        lastChunkIndex = [int]$fields[8]
    }
})

if ($documents.Count -ne $uniqueIds.Count) {
    $returnedIds = @($documents | ForEach-Object knowledgeBaseId)
    $missingIds = @($uniqueIds | Where-Object { $_ -notin $returnedIds })
    throw "指定知识库不存在：$($missingIds -join ', ')"
}

$invalid = @($documents | Where-Object {
    $_.vectorStatus -ne "COMPLETED" -or
    $_.storedChunks -lt $MinimumChunksPerDocument -or
    $_.recordedChunkCount -ne $_.storedChunks -or
    $_.distinctChunkIndexes -ne $_.storedChunks -or
    $_.firstChunkIndex -ne 0 -or
    $_.lastChunkIndex -ne ($_.storedChunks - 1)
})

if ($invalid.Count -gt 0) {
    $summary = $invalid | ForEach-Object {
        "kb=$($_.knowledgeBaseId), status=$($_.vectorStatus), stored=$($_.storedChunks), recorded=$($_.recordedChunkCount), indexes=$($_.firstChunkIndex)..$($_.lastChunkIndex), distinct=$($_.distinctChunkIndexes)"
    }
    throw "多段语料验收失败（每份至少 $MinimumChunksPerDocument 段且序号须连续）：$($summary -join '; ')"
}

$manifest = [ordered]@{
    version = "gold-v3-multichunk-corpus-observed-v1"
    observedAt = (Get-Date).ToUniversalTime().ToString("o")
    environment = [ordered]@{
        databaseContainer = $ContainerName
        database = $DatabaseName
        splitter = "Spring AI TokenTextSplitter.builder().build()"
        minimumChunksPerDocument = $MinimumChunksPerDocument
    }
    documents = @($documents | ForEach-Object {
        [ordered]@{
            knowledgeBaseId = $_.knowledgeBaseId
            documentSha256 = $_.documentSha256
            originalFilename = $_.originalFilename
            chunkCount = $_.storedChunks
            chunkIndexes = @(0..($_.storedChunks - 1))
        }
    })
}

$parent = Split-Path -Parent $OutputPath
if ($parent) {
    New-Item -ItemType Directory -Force -Path $parent | Out-Null
}
$manifest | ConvertTo-Json -Depth 6 | Set-Content -Path $OutputPath -Encoding utf8
Write-Host "Gold v3 多段语料验收通过：$($documents.Count) 份材料，manifest=$OutputPath"
