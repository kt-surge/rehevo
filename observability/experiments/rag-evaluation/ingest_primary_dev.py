"""Upload frozen public dev documents through the product; export actual queue text and chunks.

Requires the isolated rehevo-opt-20261001 runtime. No credentials or vectors are exported.
This collects ingestion evidence, not retrieval quality or performance claims.
"""
from datetime import datetime, timezone
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time

import requests

from fact_gold import digest, load_gold

ROOT = Path(__file__).resolve().parents[3]
BASE_URL = "http://127.0.0.1:18080"
PROJECT = "rehevo-opt-20261001"


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def api(path, **kwargs):
    response = requests.request("POST" if kwargs else "GET", BASE_URL + path, timeout=45, **kwargs)
    response.raise_for_status()
    value = response.json()
    if value.get("code") != 200:
        raise ValueError(f"API failed: {path}, code={value.get('code')}, message={value.get('message')}")
    return value["data"]


def docker_json(service, *args):
    raw = subprocess.check_output(["docker", "exec", f"{PROJECT}-{service}-1", *args],
                                  encoding="utf-8", errors="strict")
    return json.loads(raw)


def provider_snapshot():
    fields = ("id", "baseUrl", "model", "embeddingModel", "embeddingDimensions", "supportsEmbedding",
              "temperature", "defaultChatProvider", "defaultEmbeddingProvider")
    providers = []
    for value in api("/api/llm-provider/list"):
        row = {key: value.get(key) for key in fields}
        row["keyAvailable"] = True if value.get("maskedApiKey") not in (None, "", "***") else None
        row["keyAvailabilityNote"] = "masked configuration only; *** cannot distinguish absent from short credentials"
        providers.append(row)
    voice = {}
    for kind, names in (("asr", ("url", "model", "language", "format", "sampleRate", "enableTurnDetection",
                               "turnDetectionType", "turnDetectionThreshold", "turnDetectionSilenceDurationMs")),
                        ("tts", ("model", "voice", "format", "sampleRate", "mode", "languageType", "speechRate", "volume"))):
        value = api("/api/llm-provider/voice/" + kind)
        voice[kind] = {key: value.get(key) for key in names}
        voice[kind]["keyAvailable"] = True if value.get("maskedApiKey") not in (None, "", "***") else None
    return {"capturedAt": utc_now(), "source": "running application DTOs backed by isolated provider DB",
            "defaultProviders": api("/api/llm-provider/default-provider"), "providers": providers,
            "voice": voice, "secretValuesRecorded": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    gold = load_gold(args.gold, "agent_verified")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    requests.get(BASE_URL + "/actuator/health", timeout=10).raise_for_status()
    if api("/api/knowledgebase/list"):
        raise ValueError("Expected a fresh isolated knowledge-base DB; do not mix with previous uploads")
    write_json(output / "running-provider-config.json", provider_snapshot())
    files = [ROOT / "app/src/main/resources/application.yml", ROOT / "observability/experiments/runtime/boot-run.ps1"]
    files += list((ROOT / "app/src/main/java/interview/guide/modules/knowledgebase").rglob("*.java"))
    files += list((ROOT / "app/src/main/java/interview/guide/infrastructure/file").rglob("*.java"))
    write_json(output / "ingestion-source-hashes.json", {str(path.relative_to(ROOT)): digest(path) for path in sorted(files)})
    manifest = {"kind": "actual-primary-dev-ingestion", "startedAt": utc_now(),
                "goldManifestSha256": digest(gold.manifest_path), "apiBaseUrl": BASE_URL,
                "composeProject": PROJECT, "scope": "new isolated local DB, public official documents, same-agent dev Gold",
                "documents": []}
    write_json(output / "ingestion.manifest.json", manifest)
    for doc_id, doc in gold.documents.items():
        file_path = gold.manifest_path.parent / doc["canonicalPath"]
        started = time.perf_counter()
        with file_path.open("rb") as file:
            value = api("/api/knowledgebase/upload", files={"file": (file_path.name, file, "text/plain")},
                        data={"name": doc_id, "category": "primary-fact-dev-20261001"})
        write_json(output / f"upload-{doc_id}.json", value)
        row = {"documentId": doc_id, "knowledgeBaseId": value["knowledgeBase"]["id"],
               "officialHtmlSha256": doc["documentSha256"], "uploadedCanonicalTextSha256": digest(file_path),
               "canonicalCharacters": len(gold.source_texts[doc_id]), "uploadElapsedMs": (time.perf_counter() - started) * 1000}
        manifest["documents"].append(row)
        write_json(output / "ingestion.manifest.json", manifest)
        print(json.dumps({"uploaded": doc_id, "knowledgeBaseId": row["knowledgeBaseId"]}), flush=True)
        time.sleep(1.1)  # Product rate limit defaults to one-second windows.
    deadline = time.monotonic() + 120
    last_statuses = None
    while True:
        statuses = api("/api/knowledgebase/list")
        write_json(output / "vector-statuses.json", statuses)
        compact = [(row["id"], row["vectorStatus"], row["chunkCount"]) for row in statuses]
        if compact != last_statuses:
            print(json.dumps({"vectorStatuses": compact}), flush=True)
            last_statuses = compact
        if any(row["vectorStatus"] == "FAILED" for row in statuses):
            raise ValueError("Vectorization failed; raw status retained, stop this experiment")
        if len(statuses) == len(manifest["documents"]) and all(row["vectorStatus"] == "COMPLETED" for row in statuses):
            break
        if time.monotonic() >= deadline:
            raise TimeoutError("Confirmed jobs did not complete within 120 seconds; inspect without re-uploading")
        time.sleep(2)
    entries = docker_json("redis", "redis-cli", "--json", "XRANGE", "knowledgebase:vectorize:stream", "-", "+")
    write_json(output / "actual-vectorization-stream.json", entries)
    by_kb = {}
    for message_id, items in entries:
        fields = dict(zip(items[::2], items[1::2]))
        by_kb.setdefault(int(fields["kbId"]), []).append((message_id, fields))
    for row in manifest["documents"]:
        messages = by_kb.get(row["knowledgeBaseId"], [])
        if len(messages) != 1:
            raise ValueError("Expected exactly one parsed-text task for each fresh upload")
        message_id, fields = messages[0]
        parsed = fields["content"]
        parsed_path = output / f"parsed-{row['documentId']}.txt"
        parsed_path.write_bytes(parsed.encode("utf-8"))
        row.update(parsedTextPath=parsed_path.name, actualParsedTextSha256=digest(parsed_path),
                   parsedCharacters=len(parsed), vectorizationMessageId=message_id,
                   parserProvenance="product Tika + TextCleaningService; exact Redis Stream content")
        download = requests.get(BASE_URL + f"/api/knowledgebase/{row['knowledgeBaseId']}/download", timeout=20)
        download.raise_for_status()
        row["downloadedStoredFileSha256"] = hashlib.sha256(download.content).hexdigest()
        if row["downloadedStoredFileSha256"] != row["uploadedCanonicalTextSha256"]:
            raise ValueError("Stored-file download differs from uploaded bytes")
    kb_ids = ",".join(str(row["knowledgeBaseId"]) for row in manifest["documents"])
    sql = ("SELECT COALESCE(json_agg(row_to_json(t) ORDER BY t.kb_id,t.chunk_index),'[]'::json) FROM "
           "(SELECT id::text AS chunk_id,content AS text,metadata,metadata->>'kb_id' AS kb_id,"
           "(metadata->>'chunk_index')::int AS chunk_index FROM vector_store WHERE metadata->>'kb_id' IN ("
           + ",".join("'" + item + "'" for item in kb_ids.split(",")) + ")) t;")
    chunks = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-A", "-t", "-c", sql)
    write_json(output / "actual-chunks.json", chunks)
    manifest.update(completedAt=utc_now(), actualChunks=len(chunks), secretValuesRecorded=False,
                    vectorsRecorded=False, retrievalScoreProduced=False)
    write_json(output / "ingestion.manifest.json", manifest)
    print(json.dumps({"ingestionCompleted": True, "documents": len(manifest["documents"]), "chunks": len(chunks)}), flush=True)


if __name__ == "__main__":
    main()
