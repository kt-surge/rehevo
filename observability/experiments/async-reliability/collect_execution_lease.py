"""Seal two-consumer A/B, execution contracts and an actual durable-enabled app restart.

Run without redirecting active stdout into RUN. Initial failed attempts remain immutable.
Controlled VectorStore calls are separate from the real Embedding API smoke.
"""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

import requests

ROOT = Path(__file__).resolve().parents[3]
RUN = Path(__file__).parent / "runs/execution-lease-20261002"
sys.path.insert(0, str(ROOT / "observability/experiments/rag-evaluation"))
from experimental_index_snapshot import current, vectors_hash
from fact_gold import digest
from ingest_primary_dev import BASE_URL, api, docker_json, write_json
from collect_retry_crash import freeze_sources
from collect_status_delete import xml_summary
import finalize_vector_generation as secret_verifier


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def result(label, count, failures=0):
    invocation = read(RUN / f"{label}-invocation.json")
    value = xml_summary(RUN / f"{label}.xml")
    if (not invocation["freshIntegrationXmlCaptured"] or invocation["exitCode"] != bool(failures)
            or value["tests"] != count or value["failures"] != failures
            or value["errors"] or value["skipped"]):
        raise ValueError("Missing fresh executed result: " + label)
    return value


def runtime_settings():
    command = """
      $taskListeners=@(Get-NetTCPConnection -LocalPort 18080 -State Listen -ErrorAction Stop);
      if($taskListeners.Count -ne 1){throw 'Unique listener missing'};
      $taskApp=Get-CimInstance Win32_Process -Filter ('ProcessId='+$taskListeners[0].OwningProcess);
      [pscustomobject]@{pid=$taskApp.ProcessId;durableDisabled=$taskApp.CommandLine.Contains('--app.ai.rag.vector-task.durable-enabled=false');token800=$taskApp.CommandLine.Contains('--app.ai.rag.chunking.mode=TOKEN') -and $taskApp.CommandLine.Contains('--app.ai.rag.chunking.max-tokens=800')} | ConvertTo-Json -Compress
    """
    value = json.loads(subprocess.check_output(["pwsh", "-NoLogo", "-NoProfile", "-Command", command], text=True))
    if not value["durableDisabled"] or not value["token800"]:
        raise ValueError("Actual runtime arguments not restored")
    return value


def main():
    if any((RUN / name).exists() for name in ("comparison.json", "candidate-sources", "backend-tests", "artifacts.sha256.json")):
        raise ValueError("Evidence already frozen; do not overwrite")
    baseline = result("a-race-r1", 2, 2)
    candidate = result("b-race-final-r1", 2)
    labels = {"b-contract-final": 7, "b-durable-regression": 8, "b-recovery-regression": 2,
              "b-notification-regression": 2, "b-generation-regression": 6,
              "b-generation-race": 2, "b-fault-regression": 9}
    regressions = {label: result(label, count) for label, count in labels.items()}
    harness = "app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorExecutionRaceIntegrationTest.java"
    a_hashes = read(RUN / "a-race-r1-source-hashes.json")
    b_hashes = read(RUN / "b-race-final-r1-source-hashes.json")
    if a_hashes[harness] != b_hashes[harness] or digest(ROOT / harness) != b_hashes[harness]:
        raise ValueError("Corrected primary A/B case changed")
    observations = {}
    for label, calls, before, winner in (("a-race-r1", 2, "COMPLETED", "1"),
                                          ("b-race-final-r1", 1, "PROCESSING", "2")):
        duplicate = read(RUN / f"{label}-child/duplicate.json")
        expired = read(RUN / f"{label}-child/expired-success.json")
        if (not duplicate["twoActualConsumers"] or duplicate["callsDuringFirstExecution"] != calls
                or duplicate["finalFixtureCalls"] != calls or duplicate["pending"]
                or duplicate["status"] != "COMPLETED" or expired["oldStatusBeforeNewCompletion"] != before
                or expired["winningWorkers"] != [winner] or expired["finalFixtureCalls"] != 2
                or expired["status"] != "COMPLETED"):
            raise ValueError("A/B observed effects differ")
        observations[label] = {"duplicate": duplicate, "expiredSuccess": expired}
    controls = {}
    for label in ("b-race-final-r1", *labels):
        hashes = read(RUN / f"{label}-source-hashes.json")
        if any(digest(ROOT / path) != value for path, value in hashes.items()):
            raise ValueError("Current source differs from " + label)
        controls.update(hashes)
    contracts = {p.stem: read(p) for p in sorted((RUN / "b-contract-final-child").glob("*.json"))}
    if (len(contracts) != 7 or contracts["budget"]["actualFixtureCalls"] != 4
            or contracts["budget"]["persistentAttempts"] != 4 or contracts["budget"]["physicalNotifications"] != 7
            or not contracts["budget"]["allMessageRetryCountsZero"]
            or not contracts["budget"]["attemptPersistedBeforeExternalCall"]
            or not contracts["heartbeat"]["actualScheduledRenewal"]
            or not contracts["heartbeat"]["oldOwnerRenewRejected"]
            or not contracts["lock-wait"]["actualLockWaitObserved"]
            or not contracts["lock-wait"]["expiredPromotionRejected"]
            or not contracts["local-revocation"]["revokedLocalPromotionRejected"]):
        raise ValueError("Execution observations incomplete")
    smoke_dir = RUN / "api-restart"
    smoke = read(smoke_dir / "summary.json")
    stopped = read(smoke_dir / "old-app-stop-confirmed.json")
    restored = read(smoke_dir / "acl-restored.json")
    before = read(smoke_dir / "state-before-restart.json")
    if (not smoke["passed"] or smoke["acceptedAppPid"] == smoke["resumedAppPid"]
            or not smoke["sameAcceptedGenerationRecovered"] or smoke["manualRecoverOnceCalled"]
            or smoke["persistedExecutionAttempts"] != [1, 1] or smoke["actualVectorDimensions"] != 1024
            or not smoke["storedBytesMatch"] or not smoke["latestRetrievalOnly"]
            or not smoke["ownDatabaseAndTaskRowsDeleted"] or not smoke["originalIndexUnchanged"]
            or not smoke["postDeleteS3HeadVerified"] or not stopped["oldAppProcessMissing"]
            or not stopped["oldAppPortStopped"] or stopped["persistedExecutionAttempts"]
            or stopped["physicalNotifications"] or before["task"]["execution_attempts"]
            or before["database"]["document"]["vector_status"] != "PENDING"):
        raise ValueError("Actual application restart/product evidence incomplete")
    head_before = read(smoke_dir / "storage-before-delete.json")
    head_after = read(smoke_dir / "storage-after-delete.json")
    if (not head_before["passed"] or head_before["objectHttpStatus"] != 200
            or not head_after["passed"] or head_after["objectHttpStatus"] != 404
            or head_after["bucketHttpStatus"] != 200):
        raise ValueError("Owned storage HEAD evidence missing")
    smoke_hashes = read(smoke_dir / "source-hashes.json")
    helper_name = "observability/experiments/async-reliability/run_durable_api_restart.py"
    if digest(smoke_dir / "acceptance-helper-v1.py") != smoke_hashes[helper_name]:
        raise ValueError("Original acceptance helper not preserved")
    if any(digest(ROOT / path) != value for path, value in smoke_hashes.items() if path != helper_name):
        raise ValueError("Actual app production/probe source changed")
    controls.update({p: h for p, h in smoke_hashes.items() if p != helper_name})
    prior_verifications = {}
    for name in ("durable-delivery-20261002", "durable-notification-crash-20261002"):
        prior = RUN.parent / name
        manifest = read(prior / "artifacts.sha256.json")
        changed = [p for p, expected in manifest.items() if digest(prior / p) != expected]
        if changed:
            raise ValueError("Previous immutable evidence changed: " + name)
        prior_verifications[name] = {"frozenFiles": len(manifest), "hashMismatches": changed}
    sources = [ROOT / p for p in controls]
    sources.extend([Path(__file__), Path(__file__).parent / "run-fault-tests.ps1",
                    ROOT / helper_name, Path(__file__).parent / "storage-probe.init.gradle",
                    Path(__file__).parent / "collect_retry_crash.py", Path(__file__).parent / "collect_status_delete.py",
                    Path(__file__).parent / "finalize_vector_generation.py",
                    ROOT / "observability/experiments/runtime/boot-run.ps1",
                    ROOT / "docker/postgres/migrations/20261002_vector_execution.sql",
                    ROOT / "docker/postgres/migrations/20261002_vector_execution_rollback.sql",
                    ROOT / "docker/postgres/migrations/README.md"])
    sources = list(dict.fromkeys(sources))
    backend_paths = sorted((ROOT / "app/build/test-results/test").glob("TEST-*.xml"))
    app_java = [p for p in sources if p.suffix == ".java" and p.is_relative_to(ROOT / "app/src")]
    if not backend_paths or min(p.stat().st_mtime for p in backend_paths) < max(p.stat().st_mtime for p in app_java):
        raise ValueError("Fresh default backend regression after app Java changes required")
    default = {k: sum(int(ET.parse(p).getroot().get(k, 0)) for p in backend_paths)
               for k in ("tests", "failures", "errors", "skipped")}
    default["suites"] = len(backend_paths)
    if default["tests"] != 303 or any(default[k] for k in ("failures", "errors", "skipped")):
        raise ValueError("Default backend regression failed")
    cleanup = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-X", "-A", "-t", "-c", """
      SELECT json_build_object(
        'fixtureDocuments',(SELECT count(*) FROM knowledge_bases WHERE category IN ('async-fault-20261001','async-generation-20261001','generation-smoke-20261001','durable-restart-smoke-20261002')),
        'taskRows',(SELECT count(*) FROM kb_vector_tasks),
        'temporaryVectors',(SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' IS NOT NULL),
        'fixtureTriggers',(SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'rehevo_fault_%' OR tgname LIKE 'rehevo_delivery_%' OR tgname LIKE 'rehevo_execution_%'),
        'fixtureSchemas',(SELECT count(*) FROM pg_namespace WHERE nspname LIKE 'rehevo_generation_%' OR nspname LIKE 'rehevo_delivery_%' OR nspname LIKE 'rehevo_execution_%'));
    """)
    groups = docker_json("redis", "redis-cli", "--json", "XINFO", "GROUPS", "knowledgebase:vectorize:stream")
    decoded = [r if isinstance(r, dict) else dict(zip(r[::2], r[1::2])) for r in groups]
    group = [r for r in decoded if r.get("name") == "vectorize-group"]
    if len(group) != 1:
        raise ValueError("Unique consumer group missing")
    users = docker_json("redis", "redis-cli", "--json", "ACL", "USERS")
    cleanup.update(pendingMessages=group[0]["pending"], unreadLag=group[0]["lag"],
                   remainingFixtureAclUsers=[u for u in users if u.startswith("rehevo-fault-")])
    permission = docker_json("redis", "redis-cli", "--json", "ACL", "DRYRUN", "default", "XADD",
                             "knowledgebase:vectorize:stream", "*", "kbId", "controlled-permission-probe")
    if cleanup["unreadLag"] is None or any(cleanup.values()) or permission != "OK":
        raise ValueError("Cleanup/ACL restoration failed")
    original = read(ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001/index-backup-manifest.json")
    index_unchanged = vectors_hash(current()) == original["vectorsSha256"]
    health = requests.get(BASE_URL + "/actuator/health", timeout=10)
    health.raise_for_status()
    rows = api("/api/knowledgebase/list")
    runtime = runtime_settings()
    if (not index_unchanged or health.json().get("status") != "UP" or {r["id"] for r in rows} != {1, 2, 3, 4}
            or any(r["vectorStatus"] != "COMPLETED" for r in rows)):
        raise ValueError("Original runtime not restored")
    write_json(RUN / "preflight-failures.json", {
        "firstImmediatePortCheckRejected": not restored["oldAppPortStopped"],
        "originalXaddPermissionRestoredOnRejectedCheck": restored["originalPermissionRestored"],
        "gbkJsonReadFailedBeforeAnyNewAppStart": True,
        "correction": "Use explicit UTF-8 JSON reads; require both OS listener and old process absence, preserving socket observation.",
        "socketObservation": stopped["socketObservation"],
        "originalHelperSha256": smoke_hashes[helper_name], "finalHelperSha256": digest(ROOT / helper_name),
        "productionAppSourceChangedDuringRestartSmoke": False})
    freeze_sources(sources, RUN / "candidate-sources")
    backend_dir = RUN / "backend-tests"
    backend_dir.mkdir(exist_ok=False)
    write_json(backend_dir / "file-index.json", {p.name: f"t{n:03d}.xml" for n, p in enumerate(backend_paths)})
    for n, path in enumerate(backend_paths):
        shutil.copyfile(path, backend_dir / f"t{n:03d}.xml")
    write_json(RUN / "comparison.json", {"scope": "two actual consumers/real PostgreSQL-JPA-Redis with deterministic VectorStore; separate actual Boot restart/real Embedding/RustFS/Tika HTTP smoke",
        "baselineCorrectedPair": baseline, "candidateCorrectedPair": candidate,
        "sameCorrectedPrimaryCaseSha256": b_hashes[harness], "observations": observations,
        "executionContracts": contracts, "regressions": regressions, "defaultBackendRegression": default,
        "actualDurableEnabledRestart": smoke, "previousImmutableEvidence": prior_verifications,
        "cleanup": cleanup, "originalPublicIndexUnchanged": True, "runtimeHealth": health.json(),
        "restoredActualRuntime": runtime, "defaultDurableEnabled": False,
        "decision": "Retain execution ownership/fencing/persisted four-attempt budget; keep default disabled pending remaining crash/heartbeat-failure/load/retention gates.",
        "limits": ["First duplicate case false-passed because it awaited lag=1; corrected pair waits lag=0 and pending=1. Initial logs retained.",
                   "Primary race Java is identical in corrected A/B; base fixture wiring and durable input setup intentionally adapt to candidate APIs and are frozen separately.",
                   "Primary race manually constructs compatibility consumer without heartbeat; separate contract activates actual managed heartbeat, and actual app uses full injection.",
                   "Expired leases and retry due-times are advanced by the fixture; not actual recovery latency measurements.",
                   "Four claimed attempts are not an upper bound on provider HTTP requests or SDK retries. Controlled failures use deterministic VectorStore.",
                   "Actual restart smoke recovers an accepted but unexecuted request; it does not test process exit during external calculation.",
                   "No external exactly-once, production throughput, RAG quality, Chat or voice latency claim."]})
    secret_verifier.RUN = RUN
    leaked = secret_verifier.secret_hits()
    if leaked:
        raise ValueError("Sensitive values found; values suppressed")
    manifest = {p.relative_to(RUN).as_posix(): digest(p) for p in sorted(RUN.rglob("*")) if p.is_file()}
    write_json(RUN / "artifacts.sha256.json", manifest)
    changed = [p for p, expected in manifest.items() if digest(RUN / p) != expected]
    index = read(RUN / "candidate-sources/file-index.json")
    source_changes = [p for p, frozen in index.items() if digest(ROOT / p) != digest(RUN / "candidate-sources" / frozen)]
    if changed or source_changes:
        raise ValueError("Closed evidence/current sources changed during freeze")
    write_json(RUN / "post-freeze-verification.json", {"frozenArtifacts": len(manifest), "hashMismatches": changed,
        "currentSourcesDifferFromFrozen": source_changes, "sensitiveValueHitFiles": leaked,
        "credentialValuesRecorded": False, "runtimeHealth": "UP", "originalPublicIndexUnchanged": True})
    print(json.dumps({"baselineFailures": 2, "candidatePasses": 2, "executionContractPasses": 7,
        "defaultTests": default, "actualDurableRestartPassed": True, "frozenArtifacts": len(manifest),
        "hashMismatches": 0, "sensitiveValueHitFiles": len(leaked), "cleanup": cleanup}, ensure_ascii=False))


if __name__ == "__main__":
    main()
