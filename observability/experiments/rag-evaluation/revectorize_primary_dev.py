"""Re-index the same four public files with one explicit chunking strategy.

Requires an exact original index backup. Saves actual source/queue/chunk artifacts;
does not overwrite the A run or silently retry a failed vectorization job.
"""
from copy import deepcopy
import json
from pathlib import Path
import time

import requests

from experimental_index_snapshot import assert_scope, current, vectors_hash
from fact_gold import digest
from ingest_primary_dev import BASE_URL, ROOT, api, docker_json, provider_snapshot, utc_now, write_json

BASELINE = ROOT / "observability/experiments/rag-evaluation/runs/primary-dev-a-20261001"
RUN = ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001"


def main():
    if (RUN / "ingestion.manifest.json").exists():
        raise ValueError("Run exists; inspect without submitting duplicate jobs")
    backup = json.loads((RUN / "index-backup-manifest.json").read_text(encoding="utf-8"))
    state = current()
    assert_scope(state)
    if vectors_hash(state) != backup["vectorsSha256"]:
        raise ValueError("Current index differs from frozen A; stop before re-indexing")
    # Confirm running strategy, not just the launcher's requested flags.
    config = api("/api/knowledgebase/evaluation/retrieval", json={"queries": [{
        "knowledgeBaseIds": [1], "question": "isolation"}], "rewrite": False, "retrievalMode": "HYBRID"})
    if config["configuration"]["chunking"] != {"mode": "STRUCTURED", "maxTokens": 800}:
        raise ValueError("Running chunking config differs from experimental strategy")
    write_json(RUN / "pre-ingestion-effective-config.json", config["configuration"])
    provider = provider_snapshot()
    previous_provider = json.loads((BASELINE / "running-provider-config.json").read_text(encoding="utf-8"))
    embedding_provider = provider["defaultProviders"]["defaultEmbeddingProvider"]
    if embedding_provider != previous_provider["defaultProviders"]["defaultEmbeddingProvider"]:
        raise ValueError("Default embedding provider changed")
    keys = ("id", "baseUrl", "embeddingModel", "embeddingDimensions", "supportsEmbedding")
    actual_embedding = next(row for row in provider["providers"] if row["id"] == embedding_provider)
    baseline_embedding = next(row for row in previous_provider["providers"] if row["id"] == embedding_provider)
    if any(actual_embedding[key] != baseline_embedding[key] for key in keys):
        raise ValueError("Embedding model/endpoint/dimensions changed from A")
    write_json(RUN / "running-provider-config.json", provider)
    files = list((ROOT / "app/src/main/java/interview/guide/modules/knowledgebase").rglob("*.java"))
    files += list((ROOT / "app/src/main/java/interview/guide/infrastructure/file").rglob("*.java"))
    files += [ROOT / "app/src/main/java/interview/guide/common/config/DocumentChunkingProperties.java",
              ROOT / "app/src/main/resources/application.yml", ROOT / "observability/experiments/runtime/boot-run.ps1",
              Path(__file__)]
    write_json(RUN / "ingestion-source-hashes.json", {path.relative_to(ROOT).as_posix(): digest(path) for path in sorted(files)})
    before = docker_json("redis", "redis-cli", "--json", "XRANGE", "knowledgebase:vectorize:stream", "-", "+")
    before_ids = {row[0] for row in before}
    old = json.loads((BASELINE / "ingestion.manifest.json").read_text(encoding="utf-8"))
    manifest = deepcopy(old)
    manifest.update(kind="actual-same-files-structured-revectorization", startedAt=utc_now(),
                    strategyInfo={"mode": "STRUCTURED", "maxTokens": 800, "sourceSpans": "UTF16",
                                  "implementation": "structured-source-spans-v1"},
                    scope="same four stored files and same embedding model; same-agent dev, not independent test")
    manifest.pop("completedAt", None)
    manifest.pop("actualChunks", None)
    manifest["goldManifestSha256"] = digest(ROOT / "data/local/fact-gold-v1-20261001-r2/manifest.json")
    write_json(RUN / "ingestion.manifest.json", manifest)
    for row in manifest["documents"]:
        response = requests.post(BASE_URL + f"/api/knowledgebase/{row['knowledgeBaseId']}/revectorize", timeout=45)
        value = response.json()
        write_json(RUN / f"revectorize-{row['documentId']}.json", {"httpStatus": response.status_code, "result": value})
        if response.status_code != 200 or value.get("code") != 200:
            raise ValueError("Revectorization rejected; retain response and stop without retry")
        time.sleep(1.1)
    deadline = time.monotonic() + 120
    last = None
    while True:
        statuses = api("/api/knowledgebase/list")
        write_json(RUN / "vector-statuses.json", statuses)
        compact = [(row["id"], row["vectorStatus"], row["chunkCount"]) for row in statuses]
        if last != compact:
            print(json.dumps({"statuses": compact}), flush=True)
            last = compact
        if any(row["vectorStatus"] == "FAILED" for row in statuses):
            raise ValueError("Actual vectorization failed; no re-submission")
        if len(statuses) == 4 and all(row["vectorStatus"] == "COMPLETED" for row in statuses):
            break
        if time.monotonic() >= deadline:
            raise TimeoutError("Jobs did not complete within 120 seconds; inspect terminal state")
        time.sleep(2)
    entries = docker_json("redis", "redis-cli", "--json", "XRANGE", "knowledgebase:vectorize:stream", "-", "+")
    new_entries = [row for row in entries if row[0] not in before_ids]
    write_json(RUN / "actual-vectorization-stream.json", new_entries)
    for row in manifest["documents"]:
        matches = [(message_id, dict(zip(items[::2], items[1::2]))) for message_id, items in new_entries
                   if dict(zip(items[::2], items[1::2])).get("kbId") == str(row["knowledgeBaseId"])]
        if len(matches) != 1:
            raise ValueError("Expected exactly one new stream entry per document")
        message_id, fields = matches[0]
        target = RUN / row["parsedTextPath"]
        target.write_bytes(fields["content"].encode("utf-8"))
        if digest(target) != row["actualParsedTextSha256"]:
            raise ValueError("Parsed text changed from A: chunking is no longer the only variable")
        row["baselineVectorizationMessageId"] = row["vectorizationMessageId"]
        row["vectorizationMessageId"] = message_id
    scope = current()
    assert_scope(scope)
    chunks = [{"chunk_id": row["id"], "text": row["content"], "metadata": row["metadata"],
               "kb_id": row["metadata"]["kb_id"], "chunk_index": row["metadata"]["chunk_index"]}
              for row in scope["vectors"]]
    if not all(row["metadata"].get("chunking_mode") == "STRUCTURED" for row in chunks):
        raise ValueError("Mixed old/new index; do not score")
    write_json(RUN / "actual-chunks.json", chunks)
    manifest.update(completedAt=utc_now(), actualChunks=len(chunks), secretValuesRecorded=False,
                    vectorsRecorded=False, retrievalScoreProduced=False)
    write_json(RUN / "ingestion.manifest.json", manifest)
    print(json.dumps({"ingestionCompleted": True, "chunks": len(chunks), "sameParsedBytesAsA": True}), flush=True)


if __name__ == "__main__":
    main()
