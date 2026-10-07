"""Add four public docs through the actual product; reuse the exact original index.

Scope is the owned rehevo-opt runtime only. Existing four files and UUIDs are
verified, never uploaded again or mutated. Exported data contains no embeddings.
"""
import argparse
import hashlib
import json
import shutil
import time
from pathlib import Path

import requests

from experimental_index_snapshot import assert_scope, current, vectors_hash
from fact_gold import digest, load_gold
from ingest_primary_dev import ROOT, BASE_URL, PROJECT, api, docker_json, provider_snapshot, utc_now, write_json

OLD = ROOT / "observability/experiments/rag-evaluation/runs/primary-dev-a-20261001"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    gold = load_gold(args.gold, "agent_verified")
    before = current()
    assert_scope(before)
    if {row["id"] for row in api("/api/knowledgebase/list")} != {1, 2, 3, 4}:
        raise ValueError("Expected only the four owned original documents; inspect unexpected data")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / "original-index-before.json", {"rows": len(before["vectors"]), "sha256": vectors_hash(before)})
    write_json(output / "running-provider-config.json", provider_snapshot())
    paths = [ROOT / "app/src/main/resources/application.yml", ROOT / "observability/experiments/runtime/boot-run.ps1"]
    paths += list((ROOT / "app/src/main/java/interview/guide/modules/knowledgebase").rglob("*.java"))
    paths += list((ROOT / "app/src/main/java/interview/guide/common/ai").rglob("*Chunk*.java"))
    paths += list((ROOT / "app/src/main/java/interview/guide/common/ai").rglob("*Splitter*.java"))
    paths += list((ROOT / "app/src/main/java/interview/guide/infrastructure/file").rglob("*.java"))
    write_json(output / "ingestion-source-hashes.json", {path.relative_to(ROOT).as_posix(): digest(path) for path in sorted(set(paths))})
    old_manifest = json.loads((OLD / "ingestion.manifest.json").read_text(encoding="utf-8"))
    manifest = {"kind": "actual-expanded-development-ingestion", "startedAt": utc_now(),
                "goldManifestSha256": digest(gold.manifest_path), "apiBaseUrl": BASE_URL, "composeProject": PROJECT,
                "scope": "four exact old controlled indexes plus four new product uploads; same-Agent dev only",
                "strategyInfo": {"mode": "TOKEN", "maxTokens": 800, "overlap": False,
                                 "mixedIngestionProvenance": True,
                                 "oldIngestionManifestSha256": digest(OLD / "ingestion.manifest.json"),
                                 "note": "Old parsed bytes and vector UUIDs reused; new four use current pipeline. Chunk strategy unchanged."},
                "documents": []}
    for old_row in old_manifest["documents"]:
        row = old_row.copy()
        doc = gold.documents[row["documentId"]]
        if doc["sourceTextSha256"] != row["uploadedCanonicalTextSha256"] or doc["documentSha256"] != row["officialHtmlSha256"]:
            raise ValueError("Original source hashes do not match expanded Gold")
        shutil.copyfile(OLD / row["parsedTextPath"], output / row["parsedTextPath"])
        if digest(output / row["parsedTextPath"]) != row["actualParsedTextSha256"]:
            raise ValueError("Original parsed-text fingerprint changed")
        row.update(reusedExistingIndex=True, originalIngestionManifestSha256=digest(OLD / "ingestion.manifest.json"),
                   originalIngestionSourceHashesSha256=digest(OLD / "ingestion-source-hashes.json"))
        manifest["documents"].append(row)
    write_json(output / "ingestion.manifest.json", manifest)
    for doc_id, doc in gold.documents.items():
        if any(row["documentId"] == doc_id for row in manifest["documents"]):
            continue
        path = gold.manifest_path.parent / doc["canonicalPath"]
        start = time.perf_counter()
        with path.open("rb") as file:
            value = api("/api/knowledgebase/upload", files={"file": (path.name, file, "text/plain")},
                        data={"name": doc_id, "category": "expanded-fact-dev-20261002"})
        write_json(output / f"upload-{doc_id}.json", value)
        if value.get("duplicate") or value["knowledgeBase"]["id"] in {1, 2, 3, 4}:
            raise ValueError("Unexpected duplicate result; stop without a new upload")
        row = {"documentId": doc_id, "knowledgeBaseId": value["knowledgeBase"]["id"],
               "officialHtmlSha256": doc["documentSha256"], "uploadedCanonicalTextSha256": digest(path),
               "canonicalCharacters": len(gold.source_texts[doc_id]), "reusedExistingIndex": False,
               "uploadElapsedMs": (time.perf_counter() - start) * 1000}
        manifest["documents"].append(row)
        write_json(output / "ingestion.manifest.json", manifest)
        print(json.dumps({"uploaded": doc_id, "kbId": row["knowledgeBaseId"]}), flush=True)
        time.sleep(1.1)
    wanted = {row["knowledgeBaseId"] for row in manifest["documents"]}
    deadline, previous = time.monotonic() + 300, None
    while True:
        statuses = api("/api/knowledgebase/list")
        write_json(output / "vector-statuses.json", statuses)
        selected = [row for row in statuses if row["id"] in wanted]
        compact = [(row["id"], row["vectorStatus"], row["chunkCount"]) for row in selected]
        if compact != previous:
            print(json.dumps({"statuses": compact}), flush=True)
            previous = compact
        if any(row["vectorStatus"] == "FAILED" for row in selected):
            raise ValueError("Actual embedding failed; retain status and do not re-upload")
        if len(selected) == len(wanted) and all(row["vectorStatus"] == "COMPLETED" for row in selected):
            break
        if time.monotonic() > deadline:
            raise TimeoutError("Actual accepted tasks incomplete; inspect without resubmitting")
        time.sleep(2)
    entries = docker_json("redis", "redis-cli", "--json", "XRANGE", "knowledgebase:vectorize:stream", "-", "+")
    by_kb, owned_entries = {}, []
    for mid, items in entries:
        fields = dict(zip(items[::2], items[1::2]))
        if int(fields["kbId"]) in wanted - {1, 2, 3, 4}:
            by_kb.setdefault(int(fields["kbId"]), []).append((mid, fields))
            owned_entries.append([mid, items])
    write_json(output / "actual-new-vectorization-stream.json", owned_entries)
    for row in manifest["documents"]:
        if not row["reusedExistingIndex"]:
            if len(by_kb.get(row["knowledgeBaseId"], [])) != 1:
                raise ValueError("Expected one actual task per new doc")
            mid, fields = by_kb[row["knowledgeBaseId"]][0]
            path = output / f"parsed-{row['documentId']}.txt"
            path.write_bytes(fields["content"].encode("utf-8"))
            row.update(parsedTextPath=path.name, actualParsedTextSha256=digest(path),
                       parsedCharacters=len(fields["content"]), vectorizationMessageId=mid,
                       parserProvenance="actual Tika + cleaning Redis task payload")
        response = requests.get(BASE_URL + f"/api/knowledgebase/{row['knowledgeBaseId']}/download", timeout=30)
        response.raise_for_status()
        row["downloadedStoredFileSha256"] = hashlib.sha256(response.content).hexdigest()
        if row["downloadedStoredFileSha256"] != row["uploadedCanonicalTextSha256"]:
            raise ValueError("Product download hash differs")
    sql = ("SELECT COALESCE(json_agg(row_to_json(t) ORDER BY t.kb_id,t.chunk_index),'[]'::json) FROM "
           "(SELECT id::text AS chunk_id,content AS text,metadata,metadata->>'kb_id' AS kb_id,"
           "(metadata->>'chunk_index')::int AS chunk_index FROM vector_store WHERE metadata->>'kb_id' IN ("
           + ",".join("'" + str(kbid) + "'" for kbid in sorted(wanted)) + ")) t;")
    chunks = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-A", "-t", "-c", sql)
    write_json(output / "actual-chunks.json", chunks)
    after = current()
    assert_scope(after)
    if vectors_hash(before) != vectors_hash(after) or before["knowledgeBases"] != after["knowledgeBases"]:
        raise ValueError("Original exact index changed")
    write_json(output / "original-index-after.json", {"rows": len(after["vectors"]), "sha256": vectors_hash(after), "unchanged": True})
    manifest.update(completedAt=utc_now(), actualChunks=len(chunks), secretValuesRecorded=False,
                    embeddingsExported=False, retrievalScoreProduced=False)
    write_json(output / "ingestion.manifest.json", manifest)
    print(json.dumps({"ingested": True, "chunks": len(chunks), "oldIndexUnchanged": True}), flush=True)


if __name__ == "__main__":
    main()
