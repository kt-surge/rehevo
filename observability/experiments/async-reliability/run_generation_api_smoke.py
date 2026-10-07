"""Actual upload/revectorize/retrieval/delete path, using one disposable fixture and real Embedding.

No Chat/TTS request, Gold accuracy or performance claim. Never mutates the four public-document indexes.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys
import time
import uuid

import requests

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "observability/experiments/rag-evaluation"))
from experimental_index_snapshot import current, vectors_hash
from fact_gold import digest
from ingest_primary_dev import BASE_URL, docker_json, provider_snapshot, write_json

CATEGORY = "generation-smoke-20261001"


def call(method, path, output, **kwargs):
    response = requests.request(method, BASE_URL + path, timeout=45, **kwargs)
    value = response.json()
    write_json(output, {"capturedAtUtc": datetime.now(timezone.utc).isoformat(),
                        "httpStatus": response.status_code, "result": value})
    if response.status_code != 200 or value.get("code") != 200:
        raise ValueError(f"Actual API failed: {method} {path}; inspect saved response")
    return value["data"]


def sql(statement):
    return docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-X", "-A", "-t", "-c", statement)


def state(kb_id):
    return sql(f"""
      SELECT json_build_object(
        'document',(SELECT row_to_json(k) FROM (SELECT id,file_hash,category,vector_status,vector_error,
          vector_generation,chunk_count,storage_key FROM knowledge_bases WHERE id={kb_id}) k),
        'formalVectors',(SELECT COALESCE(json_agg(row_to_json(v) ORDER BY id),'[]'::json) FROM
          (SELECT id::text,content,metadata,vector_dims(embedding) AS dimensions FROM vector_store
            WHERE metadata->>'kb_id'='{kb_id}') v),
        'temporaryVectors',(SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id'='{kb_id}'));
      """)


def wait_complete(kb_id, expected_generation, output):
    deadline = time.monotonic() + 120
    observations = []
    while True:
        value = state(kb_id)
        observations.append({"capturedAtUtc": datetime.now(timezone.utc).isoformat(), "state": value})
        write_json(output, observations)
        document = value["document"]
        if not document or document["vector_status"] == "FAILED":
            raise ValueError("Actual vectorization failed/deleted; stop supplier requests")
        if document["vector_generation"] != expected_generation:
            raise ValueError("Unexpected concurrent request version; stop this fixture")
        if document["vector_status"] == "COMPLETED":
            if not value["formalVectors"] or value["temporaryVectors"] or document["chunk_count"] != len(value["formalVectors"]):
                raise ValueError("Completed state differs from actual vectors")
            if any(row["dimensions"] != 1024 or row["metadata"].get("kb_generation") != expected_generation
                   for row in value["formalVectors"]):
                raise ValueError("Actual dimensions/vector provenance differ")
            return value
        if time.monotonic() >= deadline:
            raise TimeoutError("Fixture still unfinished after 120 seconds; no re-upload")
        time.sleep(1)


def own_messages(kb_id):
    messages = docker_json("redis", "redis-cli", "--json", "XRANGE", "knowledgebase:vectorize:stream", "-", "+")
    result = []
    for message_id, fields in messages:
        value = dict(zip(fields[::2], fields[1::2]))
        if value.get("kbId") == str(kb_id):
            result.append({"id": message_id, "fields": value})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    original_hash = vectors_hash(current())
    write_json(output / "running-provider-config.json", provider_snapshot())
    sources = list((ROOT / "app/src/main/java/interview/guide/modules/knowledgebase").rglob("*.java"))
    sources += list((ROOT / "app/src/main/java/interview/guide/infrastructure/file").rglob("*.java"))
    sources += [ROOT / "app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java",
                ROOT / "observability/experiments/runtime/boot-run.ps1", Path(__file__)]
    write_json(output / "source-hashes.json", {path.relative_to(ROOT).as_posix(): digest(path) for path in sorted(sources)})
    marker = str(uuid.uuid4())
    content = (f"请求版本产品验收样本，标记 {marker}。\n"
               "这是受控测试材料，不是真实业务知识或检索金标。\n"
               "此样本的恢复入口是重新向量化；每次接受请求产生新的请求版本，重试沿用原版本。\n"
               "向量提升和完成状态应原子提交，旧请求不得覆盖新的请求结果。\n")
    data = content.encode("utf-8")
    content_hash = hashlib.sha256(data).hexdigest()
    (output / "fixture.txt").write_bytes(data)
    kb_id = None
    result = {"scope": "actual isolated product HTTP + RustFS/Tika/PostgreSQL/Redis + real configured Embedding",
              "fixtureMarker": marker, "fileSha256": content_hash, "generatorCalled": False,
              "goldScoreProduced": False, "productionLatencyClaim": False, "passed": False,
              "originalPublicIndexHashBefore": original_hash}
    write_json(output / "summary.json", result)
    try:
        upload = call("POST", "/api/knowledgebase/upload", output / "upload.json",
                      files={"file": (f"generation-smoke-{marker}.txt", data, "text/plain")},
                      data={"name": f"generation-smoke-{marker}", "category": CATEGORY})
        kb_id = int(upload["knowledgeBase"]["id"])
        if kb_id <= 4:
            raise ValueError("Upload did not allocate a new disposable document")
        result["knowledgeBaseId"] = kb_id
        before = state(kb_id)
        if before["document"]["file_hash"] != content_hash or before["document"]["category"] != CATEGORY:
            raise ValueError("Uploaded row is not the exact owned fixture")
        first_generation = before["document"]["vector_generation"]
        uuid.UUID(first_generation)
        first = wait_complete(kb_id, first_generation, output / "upload-state-observations.json")
        download = requests.get(BASE_URL + f"/api/knowledgebase/{kb_id}/download", timeout=20)
        download.raise_for_status()
        if hashlib.sha256(download.content).hexdigest() != content_hash:
            raise ValueError("Actual stored-file download differs")
        result["storedFileDownloadMatches"] = True
        # This is the actual HTTP revectorize entry, distinct from the controlled SQL race pair.
        call("POST", f"/api/knowledgebase/{kb_id}/revectorize", output / "revectorize.json")
        second_generation = state(kb_id)["document"]["vector_generation"]
        uuid.UUID(second_generation)
        if second_generation == first_generation:
            raise ValueError("Accepted HTTP request did not allocate a new version")
        second = wait_complete(kb_id, second_generation, output / "revectorize-state-observations.json")
        if {row["id"] for row in first["formalVectors"]} & {row["id"] for row in second["formalVectors"]}:
            raise ValueError("HTTP revectorize did not replace the old formal vectors")
        messages = own_messages(kb_id)
        write_json(output / "own-stream-messages.json", messages)
        if len(messages) != 2 or {row["fields"].get("generation") for row in messages} != {first_generation, second_generation}:
            raise ValueError("Expected exactly the actual upload and revectorize messages")
        payload = {"queries": [{"knowledgeBaseIds": [kb_id], "question": "该请求版本验收样本的恢复入口是什么？"}],
                   "rewrite": False, "retrievalMode": "HYBRID"}
        write_json(output / "retrieval-request.json", payload)
        retrieval = call("POST", "/api/knowledgebase/evaluation/retrieval", output / "retrieval.json", json=payload)
        evidence = retrieval["items"][0]["evidence"]
        current_ids = {row["id"] for row in second["formalVectors"]}
        if not evidence or any(row["vectorDocumentId"] not in current_ids for row in evidence):
            raise ValueError("Actual retrieval did not use the latest owned vectors")
        result.update(passed=True, generations=[first_generation, second_generation],
                      uploadVectorCount=len(first["formalVectors"]), revectorizeVectorCount=len(second["formalVectors"]),
                      vectorDimensions=1024, retrievalEvidenceCount=len(evidence),
                      latestVectorIdsOnly=True, actualUploadAndRevectorizeMessages=2)
    except Exception as exc:
        result["failureType"] = type(exc).__name__
        # Failure responses are saved separately; never export provider authentication strings.
        raise
    finally:
        if kb_id is not None and kb_id > 4:
            owned = state(kb_id)["document"]
            if owned and owned["file_hash"] == content_hash and owned["category"] == CATEGORY:
                call("DELETE", f"/api/knowledgebase/{kb_id}", output / "delete.json")
            elif owned:
                raise ValueError("Ownership changed; no cleanup mutation allowed")
            after = state(kb_id)
            write_json(output / "after-delete-state.json", after)
            if after["document"] or after["formalVectors"] or after["temporaryVectors"]:
                raise ValueError("Own fixture DB/vector cleanup incomplete")
            result["ownDocumentAndVectorsDeleted"] = True
        result["originalPublicIndexHashAfter"] = vectors_hash(current())
        result["originalPublicIndexUnchanged"] = result["originalPublicIndexHashAfter"] == original_hash
        result["s3ObjectAbsenceAfterDeleteVerified"] = False
        result["limits"] = ["One short synthetic document and one query; no retrieval quality or latency A/B.",
                            "Stored bytes were verified through actual download before delete; post-delete S3 HEAD not checked.",
                            "Chat/TTS and full interview/evaluation paths remain unverified in this experiment."]
        write_json(output / "summary.json", result)
        if not result["originalPublicIndexUnchanged"]:
            raise ValueError("Original public index fingerprint changed")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
