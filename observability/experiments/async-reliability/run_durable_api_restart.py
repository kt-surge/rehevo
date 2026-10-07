"""Actual app restart and configured Embedding recovery on one owned disposable document.

Phases are separate so the app process is controlled by verified exec handles. Only the dedicated
rehevo-opt Redis instance temporarily denies default XADD. Restore it after stopping the old app.
No manual recoverOnce, Chat/TTS, Gold quality or latency claims.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "observability/experiments/rag-evaluation"))
from experimental_index_snapshot import current, vectors_hash
from fact_gold import digest
from ingest_primary_dev import BASE_URL, docker_json, provider_snapshot, write_json
from run_generation_api_smoke import call, own_messages, sql, state, wait_complete

CATEGORY = "durable-restart-smoke-20261002"
STREAM = "knowledgebase:vectorize:stream"


def app_pid():
    command = "(Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction Stop).OwningProcess"
    value = subprocess.check_output(["pwsh", "-NoLogo", "-NoProfile", "-Command", command], text=True).strip()
    return int(value)


def task(generation):
    uuid.UUID(generation)
    return sql("SELECT row_to_json(t) FROM (SELECT generation,kb_id,content_sha256,chunking_mode,max_tokens,state,"
               "execution_owner,execution_fence,execution_attempts,max_execution_attempts,delivery_attempts,"
               "last_message_id,last_delivery_error FROM kb_vector_tasks WHERE generation='" + generation + "') t")


def head_owned(output, key, marker, expected):
    runtime = ROOT / "data/local/rehevo-opt-runtime-20261001"
    settings = {}
    allowed = {"RUSTFS_ACCESS_KEY", "RUSTFS_SECRET_KEY", "APP_STORAGE_ENDPOINT", "APP_STORAGE_BUCKET", "APP_STORAGE_REGION"}
    for path in (ROOT / ".env", runtime / ".env"):
        if path.exists():
            for line in path.read_text(encoding="utf-8-sig").splitlines():
                name, separator, value = line.partition("=")
                if separator and name in allowed:
                    settings[name] = value.strip().strip("\"'")
    settings.setdefault("APP_STORAGE_REGION", "us-east-1")
    if not allowed.issubset(settings):
        raise ValueError("Exact storage environment unavailable; do not infer credentials")
    classpath = (runtime / "storage-probe.classpath").read_text(encoding="utf-8-sig")
    probe = Path(__file__).parent / "RehevoOwnedObjectHead.java"
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", suffix=".args", delete=False) as arguments:
        argument_path = Path(arguments.name)
        arguments.write('-cp\n"' + classpath.replace('\\', '/') + '"\n--source\n21\n"'
                        + str(probe).replace('\\', '/') + '"\n"' + key + '"\n' + marker + '\n' + expected + '\n')
    try:
        result = subprocess.run([r"D:\JAVA\JDK21\bin\java.exe", "@" + str(argument_path)],
                                env={**os.environ, **settings}, capture_output=True, text=True, timeout=45)
    finally:
        argument_path.unlink()
    rows = [line for line in result.stdout.splitlines() if line.startswith("{")]
    value = json.loads(rows[-1]) if rows else {"passed": False, "failureType": "ProbeDidNotProduceJson"}
    value["javaExitCode"] = result.returncode
    write_json(output / ("storage-before-delete.json" if expected == "EXISTS" else "storage-after-delete.json"), value)
    if result.returncode or not value["passed"]:
        raise ValueError("Owned HEAD probe failed; inspect saved safe status")
    return value


def require_owned(checkpoint):
    kb_id = checkpoint["knowledgeBaseId"]
    value = state(kb_id)
    parent = value["document"]
    if (kb_id <= 4 or not parent or parent["category"] != CATEGORY
            or parent["file_hash"] != checkpoint["fileSha256"]
            or parent["vector_generation"] != checkpoint["acceptedGeneration"]):
        raise ValueError("Owned document/generation changed; no mutation permitted")
    return value


def accept(output):
    output.mkdir(parents=True, exist_ok=False)
    if sql("SELECT json_build_object('tasks',(SELECT count(*) FROM kb_vector_tasks),'unfinished',"
           "(SELECT count(*) FROM knowledge_bases WHERE vector_status IN ('PENDING','PROCESSING')))") != {"tasks": 0, "unfinished": 0}:
        raise ValueError("Restart experiment requires an idle owned database")
    before_hash = vectors_hash(current())
    write_json(output / "provider-config.json", provider_snapshot())
    sources = list((ROOT / "app/src/main/java/interview/guide/modules/knowledgebase").rglob("*.java"))
    sources += list((ROOT / "app/src/main/java/interview/guide/common/config").glob("*Vector*java"))
    sources += [ROOT / "app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java",
                ROOT / "app/src/main/resources/application.yml", Path(__file__)]
    sources += [Path(__file__).parent / "RehevoOwnedObjectHead.java",
                ROOT / "app/src/main/java/interview/guide/common/config/S3Config.java",
                ROOT / "app/src/main/java/interview/guide/common/config/StorageConfigProperties.java"]
    write_json(output / "source-hashes.json", {p.relative_to(ROOT).as_posix(): digest(p) for p in sorted(sources)})
    marker = str(uuid.uuid4())
    content = (f"持久化恢复产品验收，标记 {marker}。\n"
               "这是一份独立受控测试材料，不是真实业务知识或检索金标。\n"
               "该样本的恢复入口是重新向量化；已接受请求由任务快照自动恢复，沿用原请求版本。\n"
               "恢复后的索引、文档完成状态和任务完成状态应原子提交。\n")
    data = content.encode("utf-8")
    (output / "fixture.txt").write_bytes(data)
    # DRYRUN checks permission without actually creating a notification or mutating the stream.
    permission = docker_json("redis", "redis-cli", "--json", "ACL", "DRYRUN", "default", "XADD", STREAM, "*", "kbId", "controlled-permission-probe")
    if permission != "OK":
        raise ValueError("Initial isolated XADD permission is not the expected allowed state")
    checkpoint = {"capturedAtUtc": datetime.now(timezone.utc).isoformat(), "acceptedAppPid": app_pid(),
                  "fixtureMarker": marker, "fileSha256": hashlib.sha256(data).hexdigest(),
                  "originalIndexHash": before_hash, "aclTemporarilyDenied": False,
                  "appDurableRequested": True, "recoveryMethodCalledByTest": False,
                  "productionLatencyClaim": False}
    try:
        if docker_json("redis", "redis-cli", "--json", "ACL", "SETUSER", "default", "-xadd") != "OK":
            raise ValueError("Cannot create owned notification outage")
        checkpoint["aclTemporarilyDenied"] = True
        write_json(output / "checkpoint.json", checkpoint)
        response = call("POST", "/api/knowledgebase/upload", output / "upload.json",
                        files={"file": (f"durable-restart-{marker}.txt", data, "text/plain")},
                        data={"name": f"durable-restart-{marker}", "category": CATEGORY})
        kb_id = int(response["knowledgeBase"]["id"])
        checkpoint["knowledgeBaseId"] = kb_id
        value = state(kb_id)
        checkpoint["acceptedGeneration"] = value["document"]["vector_generation"]
        checkpoint["storageKey"] = value["document"]["storage_key"]
        uuid.UUID(checkpoint["acceptedGeneration"])
        write_json(output / "checkpoint.json", checkpoint)
        require_owned(checkpoint)
        accepted = task(checkpoint["acceptedGeneration"])
        if (not accepted or accepted["state"] != "ACTIVE" or accepted["execution_attempts"] != 0
                or accepted["last_message_id"] is not None or value["document"]["vector_status"] != "PENDING"
                or value["formalVectors"] or value["temporaryVectors"] or own_messages(kb_id)):
            raise ValueError("Actual app did not accept an unexecuted durable request under XADD denial")
        write_json(output / "state-before-restart.json", {"database": value, "task": accepted})
        write_json(output / "checkpoint.json", checkpoint)
        print(json.dumps({"phase": "accepted-while-notification-denied", "knowledgeBaseId": kb_id,
                          "status": "PENDING", "persistedAttempts": 0, "physicalNotifications": 0,
                          "acceptedAppPid": checkpoint["acceptedAppPid"], "restoreAclAfterStoppingThisApp": True}))
    except Exception:
        if checkpoint["aclTemporarilyDenied"]:
            docker_json("redis", "redis-cli", "--json", "ACL", "SETUSER", "default", "+xadd")
            write_json(output / "acceptance-failure.json", {"aclRestored": True, "testPassed": False,
                                                          "credentialValuesRecorded": False})
        if "knowledgeBaseId" in checkpoint:
            parent = state(checkpoint["knowledgeBaseId"])["document"]
            if parent and parent["category"] == CATEGORY and parent["file_hash"] == checkpoint["fileSha256"]:
                call("DELETE", f"/api/knowledgebase/{checkpoint['knowledgeBaseId']}", output / "failed-accept-cleanup.json")
        raise


def restore_acl(output):
    checkpoint = json.loads((output / "checkpoint.json").read_text(encoding="utf-8-sig"))
    if not checkpoint["aclTemporarilyDenied"] or (output / "acl-restored.json").exists():
        raise ValueError("No unique active permission outage to restore")
    try:
        with socket.create_connection(("127.0.0.1", 18080), timeout=1):
            stopped = False
    except OSError as error:
        stopped = isinstance(error, ConnectionRefusedError)
    # Restoring original permission takes priority even if the phase ordering was incorrect.
    restored = docker_json("redis", "redis-cli", "--json", "ACL", "SETUSER", "default", "+xadd") == "OK"
    value = require_owned(checkpoint)
    write_json(output / "acl-restored.json", {"oldAppPortStopped": stopped, "originalPermissionRestored": restored,
                                             "database": value, "task": task(checkpoint["acceptedGeneration"])})
    if not stopped or not restored or value["document"]["vector_status"] != "PENDING":
        raise ValueError("Actual app stop/pending/original permission gate failed")
    print(json.dumps({"phase": "old-app-stopped-and-permission-restored", "oldAppPortStopped": stopped,
                      "status": "PENDING", "originalXaddPermissionRestored": restored}))


def verify_stopped(output):
    checkpoint = json.loads((output / "checkpoint.json").read_text(encoding="utf-8-sig"))
    restored = json.loads((output / "acl-restored.json").read_text(encoding="utf-8-sig"))
    if not restored["originalPermissionRestored"] or (output / "old-app-stop-confirmed.json").exists():
        raise ValueError("Original permission missing or stop confirmation already recorded")
    try:
        with socket.create_connection(("127.0.0.1", 18080), timeout=1):
            raise ValueError("Old application port still live; wait without starting another app")
    except (ConnectionRefusedError, TimeoutError) as error:
        socket_observation = type(error).__name__
    listeners = subprocess.check_output(["pwsh", "-NoLogo", "-NoProfile", "-Command",
        "@(Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction SilentlyContinue).Count"],
        text=True).strip()
    old_pid = int(checkpoint["acceptedAppPid"])
    live = subprocess.check_output(["pwsh", "-NoLogo", "-NoProfile", "-Command",
        f"[bool](Get-Process -Id {old_pid} -ErrorAction SilentlyContinue)"], text=True).strip()
    value = require_owned(checkpoint)
    accepted = task(checkpoint["acceptedGeneration"])
    permission = docker_json("redis", "redis-cli", "--json", "ACL", "DRYRUN", "default", "XADD", STREAM,
                            "*", "kbId", "controlled-permission-probe")
    if (live != "False" or listeners != "0" or permission != "OK" or value["document"]["vector_status"] != "PENDING"
            or accepted["execution_attempts"] != 0 or own_messages(checkpoint["knowledgeBaseId"])):
        raise ValueError("Actual stopped/unexecuted/no-message gate not proven")
    write_json(output / "old-app-stop-confirmed.json", {"oldAppProcessMissing": True, "oldAppPortStopped": True,
        "originalXaddPermissionRestored": True, "sameAcceptedGenerationPending": True,
        "persistedExecutionAttempts": 0, "physicalNotifications": 0,
        "socketObservation": socket_observation, "authoritativeListenerCount": int(listeners),
        "initialImmediatePortCheckRejected": not restored["oldAppPortStopped"],
        "confirmationSourceSha256": digest(Path(__file__)), "capturedAtUtc": datetime.now(timezone.utc).isoformat()})
    print(json.dumps({"phase": "actual-old-app-stop-confirmed", "oldAppProcessMissing": True,
                      "oldAppPortStopped": True, "persistedExecutionAttempts": 0, "physicalNotifications": 0}))


def resume(output):
    if (output / "summary.json").exists():
        raise ValueError("Resume result exists; do not overwrite")
    checkpoint = json.loads((output / "checkpoint.json").read_text(encoding="utf-8-sig"))
    restored = json.loads((output / "old-app-stop-confirmed.json").read_text(encoding="utf-8-sig"))
    if not restored["oldAppPortStopped"] or not restored["oldAppProcessMissing"] or not restored["originalXaddPermissionRestored"]:
        raise ValueError("Actual stop/permission restoration missing")
    resumed_pid = app_pid()
    if resumed_pid == checkpoint["acceptedAppPid"]:
        raise ValueError("App did not actually restart")
    kb_id = checkpoint["knowledgeBaseId"]
    result = {"scope": "actual Spring Boot app restart + PostgreSQL/Redis/RustFS/Tika + configured real Embedding",
              "passed": False, "acceptedAppPid": checkpoint["acceptedAppPid"], "resumedAppPid": resumed_pid,
              "sameAcceptedGenerationRecovered": False, "manualRecoverOnceCalled": False,
              "productionLatencyOrExactlyOnceClaim": False, "appDurableEnabled": True}
    try:
        require_owned(checkpoint)
        recovered = wait_complete(kb_id, checkpoint["acceptedGeneration"], output / "restart-observations.json")
        first_task = task(checkpoint["acceptedGeneration"])
        if first_task["state"] != "COMPLETED" or first_task["execution_attempts"] != 1:
            raise ValueError("Recovered task and actual index did not complete together with one claimed attempt")
        (output / "recovered-task.json").write_text(json.dumps(first_task, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        data = (output / "fixture.txt").read_bytes()
        import requests
        download = requests.get(BASE_URL + f"/api/knowledgebase/{kb_id}/download", timeout=20)
        download.raise_for_status()
        if download.content != data:
            raise ValueError("Actual stored bytes differ from accepted fixture")
        head_before = head_owned(output, checkpoint["storageKey"], checkpoint["fixtureMarker"], "EXISTS")
        if head_before["contentLength"] != len(data):
            raise ValueError("Owned HEAD byte length differs")
        call("POST", f"/api/knowledgebase/{kb_id}/revectorize", output / "revectorize.json")
        second_generation = state(kb_id)["document"]["vector_generation"]
        if second_generation == checkpoint["acceptedGeneration"]:
            raise ValueError("Actual revectorize did not accept a new request")
        second = wait_complete(kb_id, second_generation, output / "revectorize-observations.json")
        second_task = task(second_generation)
        if second_task["state"] != "COMPLETED" or second_task["execution_attempts"] != 1:
            raise ValueError("Actual new request did not use a fresh persisted budget")
        write_json(output / "revectorized-task.json", second_task)
        retrieval = call("POST", "/api/knowledgebase/evaluation/retrieval", output / "retrieval.json", json={
            "queries": [{"knowledgeBaseIds": [kb_id], "question": "该样本的恢复入口是什么？"}],
            "rewrite": False, "retrievalMode": "HYBRID"})
        evidence = retrieval["items"][0]["evidence"]
        new_ids = {row["id"] for row in second["formalVectors"]}
        if not evidence or any(row["vectorDocumentId"] not in new_ids for row in evidence):
            raise ValueError("Retrieval does not point only to the newest actual vectors")
        messages = own_messages(kb_id)
        write_json(output / "notifications.json", messages)
        if ({row["fields"].get("generation") for row in messages} != {checkpoint["acceptedGeneration"], second_generation}
                or len(messages) < 2):
            raise ValueError("Actual notifications differ from both accepted generations")
        result.update(passed=True, sameAcceptedGenerationRecovered=True, generations=[checkpoint["acceptedGeneration"], second_generation],
                      persistedExecutionAttempts=[first_task["execution_attempts"], second_task["execution_attempts"]],
                      storedBytesMatch=True, actualVectorDimensions=1024, latestRetrievalOnly=True,
                      physicalNotifications=len(messages), recoveredFormalVectors=len(recovered["formalVectors"]))
    finally:
        parent = state(kb_id)["document"]
        if parent and parent["category"] == CATEGORY and parent["file_hash"] == checkpoint["fileSha256"]:
            call("DELETE", f"/api/knowledgebase/{kb_id}", output / "delete.json")
        elif parent:
            raise ValueError("Ownership changed; cannot delete")
        after = state(kb_id)
        tasks_left = sql(f"SELECT json_build_object('count',count(*)) FROM kb_vector_tasks WHERE kb_id={kb_id}")["count"]
        write_json(output / "after-delete.json", {"database": after, "taskRows": tasks_left})
        result["ownDatabaseAndTaskRowsDeleted"] = not after["document"] and not after["formalVectors"] and not after["temporaryVectors"] and tasks_left == 0
        result["originalIndexUnchanged"] = vectors_hash(current()) == checkpoint["originalIndexHash"]
        head_after = head_owned(output, checkpoint["storageKey"], checkpoint["fixtureMarker"], "ABSENT")
        result["postDeleteS3HeadVerified"] = head_after["objectHttpStatus"] == 404 and head_after["bucketHttpStatus"] == 200
        write_json(output / "summary.json", result)
        if not result["ownDatabaseAndTaskRowsDeleted"] or not result["originalIndexUnchanged"]:
            raise ValueError("Owned cleanup/original index gate failed")
    print(json.dumps(result, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phase", choices=("accept", "restore-acl", "verify-stopped", "resume"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    if not output.is_relative_to(ROOT / "observability/experiments/async-reliability/runs"):
        raise ValueError("Evidence must remain in the owned experiment directory")
    {"accept": accept, "restore-acl": restore_acl, "verify-stopped": verify_stopped, "resume": resume}[args.phase](output)


if __name__ == "__main__":
    main()
