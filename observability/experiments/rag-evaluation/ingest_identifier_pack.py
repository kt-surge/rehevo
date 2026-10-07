"""Ingest test-only public docs beside the owned dev corpus, never mix query scopes."""
import argparse
import hashlib
import json
import time
from pathlib import Path

import requests

from experimental_index_snapshot import assert_scope, current, vectors_hash
from fact_gold import digest, load_gold
from prepare_identifier_sources import verify_strategy
from ingest_primary_dev import ROOT, BASE_URL, PROJECT, api, docker_json, provider_snapshot, utc_now, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--existing-run", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    strategy = verify_strategy()
    gold = load_gold(args.gold, "agent_verified")
    if any(doc["split"] != "test" for doc in gold.documents.values()):
        raise ValueError("Test-only ingestion required")
    old = json.loads((args.existing_run / "ingestion.manifest.json").read_text(encoding="utf-8"))
    expected = {row["knowledgeBaseId"]: row["uploadedCanonicalTextSha256"] for row in old["documents"]}
    actual = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-A", "-t", "-c",
                         "SELECT json_agg(json_build_object('id',id,'sha',file_hash,'status',vector_status)) FROM knowledge_bases;")
    if {row["id"]: row["sha"] for row in actual} != expected or any(row["status"] != "COMPLETED" for row in actual):
        raise ValueError("Unexpected existing KBs or incomplete tasks")
    original = current()
    assert_scope(original)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / "running-provider-config.json", provider_snapshot())
    paths = list((ROOT / "app/src/main/java/interview/guide/modules/knowledgebase").rglob("*.java"))
    paths += list((ROOT / "app/src/main/java/interview/guide/infrastructure/file").rglob("*.java"))
    paths += [ROOT / "app/src/main/resources/application.yml"]
    write_json(output / "ingestion-source-hashes.json", {p.relative_to(ROOT).as_posix(): digest(p) for p in sorted(paths)})
    manifest = {"kind": "actual-new-source-test-ingestion", "startedAt": utc_now(), "composeProject": PROJECT,
                "goldManifestSha256": digest(gold.manifest_path), "strategyFreezeSha256": strategy,
                "scope": "only these three new docs selected during test; all twelve exposed indexes untouched",
                "strategyInfo": {"mode": "TOKEN", "maxTokens": 800, "overlap": False}, "documents": []}
    write_json(output / "ingestion.manifest.json", manifest)
    for doc_id, doc in gold.documents.items():
        path = gold.manifest_path.parent / doc["canonicalPath"]
        began = time.perf_counter()
        with path.open("rb") as file:
            value = api("/api/knowledgebase/upload", files={"file": (path.name, file, "text/plain")},
                        data={"name": doc_id, "category": "heldout-fact-test-20261002"})
        write_json(output / f"upload-{doc_id}.json", value)
        if value.get("duplicate") or value["knowledgeBase"]["id"] in expected:
            raise ValueError("Unexpected deduplication; stop without resubmission")
        row = {"documentId": doc_id, "knowledgeBaseId": value["knowledgeBase"]["id"],
               "officialHtmlSha256": doc["documentSha256"], "uploadedCanonicalTextSha256": digest(path),
               "canonicalCharacters": len(gold.source_texts[doc_id]), "uploadElapsedMs": (time.perf_counter() - began) * 1000}
        manifest["documents"].append(row)
        write_json(output / "ingestion.manifest.json", manifest)
        print(json.dumps({"uploaded": doc_id, "kbId": row["knowledgeBaseId"]}), flush=True)
        time.sleep(1.1)
    ids = {row["knowledgeBaseId"] for row in manifest["documents"]}
    deadline = time.monotonic() + 300
    while True:
        statuses = [row for row in api("/api/knowledgebase/list") if row["id"] in ids]
        write_json(output / "vector-statuses.json", statuses)
        if any(row["vectorStatus"] == "FAILED" for row in statuses):
            raise ValueError("Actual test embedding failed; no retry")
        if len(statuses) == len(ids) and all(row["vectorStatus"] == "COMPLETED" for row in statuses):
            break
        if time.monotonic() > deadline:
            raise TimeoutError("Accepted task pending; inspect without re-upload")
        time.sleep(2)
    entries = docker_json("redis", "redis-cli", "--json", "XRANGE", "knowledgebase:vectorize:stream", "-", "+")
    owned, by_kb = [], {}
    for mid, items in entries:
        fields = dict(zip(items[::2], items[1::2]))
        if int(fields["kbId"]) in ids:
            owned.append([mid, items])
            by_kb.setdefault(int(fields["kbId"]), []).append((mid, fields))
    write_json(output / "actual-vectorization-stream.json", owned)
    for row in manifest["documents"]:
        if len(by_kb.get(row["knowledgeBaseId"], [])) != 1:
            raise ValueError("Unexpected task count")
        mid, fields = by_kb[row["knowledgeBaseId"]][0]
        path = output / f"parsed-{row['documentId']}.txt"
        path.write_bytes(fields["content"].encode("utf-8"))
        row.update(parsedTextPath=path.name, actualParsedTextSha256=digest(path), parsedCharacters=len(fields["content"]),
                   vectorizationMessageId=mid, parserProvenance="actual product Tika/cleaning Redis payload")
        response = requests.get(BASE_URL + f"/api/knowledgebase/{row['knowledgeBaseId']}/download", timeout=30)
        response.raise_for_status()
        row["downloadedStoredFileSha256"] = hashlib.sha256(response.content).hexdigest()
        if row["downloadedStoredFileSha256"] != row["uploadedCanonicalTextSha256"]:
            raise ValueError("Stored file changed")
    sql = ("SELECT json_agg(row_to_json(t) ORDER BY t.kb_id,t.chunk_index) FROM "
           "(SELECT id::text AS chunk_id,content AS text,metadata,metadata->>'kb_id' AS kb_id,"
           "(metadata->>'chunk_index')::int AS chunk_index FROM vector_store WHERE metadata->>'kb_id' IN ("
           + ",".join("'" + str(i) + "'" for i in sorted(ids)) + ")) t;")
    chunks = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-A", "-t", "-c", sql)
    write_json(output / "actual-chunks.json", chunks)
    after = current()
    assert_scope(after)
    if vectors_hash(after) != vectors_hash(original) or after["knowledgeBases"] != original["knowledgeBases"]:
        raise ValueError("Original exact indexes changed")
    write_json(output / "original-index-verification.json", {"sha256": vectors_hash(after), "unchanged": True})
    manifest.update(completedAt=utc_now(), actualChunks=len(chunks), generatorCalled=False, testScoreProduced=False)
    write_json(output / "ingestion.manifest.json", manifest)
    print(json.dumps({"ingestionComplete": True, "chunks": len(chunks), "strategyUnchanged": verify_strategy() == strategy}), flush=True)


if __name__ == "__main__":
    main()
