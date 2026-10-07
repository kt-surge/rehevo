"""Freeze actual in-calculation JVM exit A/B and database-rejected heartbeat recovery.

Never redirect live collector stdout into RUN. Existing execution-lease evidence stays immutable.
"""
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET

import requests

ROOT = Path(__file__).resolve().parents[3]
RUN = Path(__file__).parent / "runs/execution-crash-20261002"
PRIOR = RUN.parent / "execution-lease-20261002"
sys.path.insert(0, str(ROOT / "observability/experiments/rag-evaluation"))
from experimental_index_snapshot import current, vectors_hash
from fact_gold import digest
from ingest_primary_dev import api, BASE_URL, docker_json, write_json
from collect_retry_crash import freeze_sources
import collect_execution_lease as checks
import finalize_vector_generation as secret_verifier


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main():
    if any((RUN / name).exists() for name in ("comparison.json", "candidate-sources", "backend-tests", "artifacts.sha256.json")):
        raise ValueError("Evidence exists; do not overwrite")
    checks.RUN = RUN
    baseline = checks.result("a-failure-r1", 2, 1)
    candidate = checks.result("b-failure", 2)
    labels = {"b-execution-contract": 7, "b-execution-race": 2, "b-durable-contract": 8,
              "b-generation-contract": 6, "b-generation-race": 2, "b-faults": 9}
    regressions = {label: checks.result(label, count) for label, count in labels.items()}
    failed_compile = read(RUN / "a-failure-invocation.json")
    if failed_compile["freshIntegrationXmlCaptured"] or failed_compile["exitCode"] != 1:
        raise ValueError("Initial compile-only failure record changed")
    primary = "app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorExecutionFailureIntegrationTest.java"
    child = "app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorExecutionCrashWorker.java"
    a_hashes = read(RUN / "a-failure-r1-source-hashes.json")
    b_hashes = read(RUN / "b-failure-source-hashes.json")
    for path in (primary, child):
        if a_hashes[path] != b_hashes[path] or digest(ROOT / path) != b_hashes[path]:
            raise ValueError("Corrected A/B test/actual child changed")
    production_changes = [p for p in a_hashes if p.startswith("app/src/main/") and a_hashes[p] != b_hashes[p]]
    if set(production_changes) != {
            "app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java",
            "app/src/main/java/interview/guide/modules/knowledgebase/repository/VectorRepository.java"}:
        raise ValueError("A/B production mutation exceeds scoped temporary cleanup")
    baseline_sources = read(RUN / "baseline-mutation-sources/file-index.json")
    for path, frozen in baseline_sources.items():
        if digest(RUN / "baseline-mutation-sources" / frozen) != a_hashes[path]:
            raise ValueError("Actual pre-fix production source not preserved")
    observations = {}
    for label, remaining in (("a-failure-r1", 1), ("b-failure", 0)):
        checkpoint = read(RUN / f"{label}-child/execution-crash-child/checkpoint.json")
        recovered = read(RUN / f"{label}-child/execution-crash-child/recovery.json")
        heartbeat = read(RUN / f"{label}-child/heartbeat-error-child/recovery.json")
        if (checkpoint["haltCode"] != 76 or checkpoint["phase"] != "INSIDE_VECTORSTORE_AFTER_TEMPORARY_BATCH"
                or checkpoint["transactionActiveDuringExternalCall"] or checkpoint["persistedAttempts"] != 1
                or checkpoint["shutdownHooksOrFinallyCalled"] or recovered["actualChildExitCode"] != 76
                or not recovered["sameGeneration"] or recovered["persistedAttemptsAfterRestart"] != 2
                or recovered["persistedFenceAfterRestart"] != 2 or recovered["restartFixtureCalls"] != 1
                or recovered["originalPendingAfterRecovery"] or recovered["temporaryVectorsAfterRecovery"] != remaining
                or not heartbeat["actualDatabaseRenewalRejected"]
                or not heartbeat["actualScheduledHeartbeatRevokedLocalGuard"]
                or not heartbeat["databaseLeaseStillValidAtRevocation"]
                or not heartbeat["revokedExecutionPromotionRejected"]
                or heartbeat["temporaryVectorsAfterRecovery"] or heartbeat["persistedAttemptsAfterRecovery"] != 2):
            raise ValueError("Actual crash/heartbeat observations differ")
        observations[label] = {"checkpoint": checkpoint, "recovered": recovered, "heartbeat": heartbeat}
    controls = {}
    for label in ("b-failure", *labels):
        hashes = read(RUN / f"{label}-source-hashes.json")
        if any(digest(ROOT / path) != expected for path, expected in hashes.items()):
            raise ValueError("Invocation sources changed: " + label)
        controls.update(hashes)
    prior_manifest = read(PRIOR / "artifacts.sha256.json")
    prior_changes = [name for name, expected in prior_manifest.items() if digest(PRIOR / name) != expected]
    if prior_changes:
        raise ValueError("Prior immutable execution evidence changed")
    smoke = read(RUN / "api-smoke/summary.json")
    smoke_runtime = read(RUN / "api-smoke/runtime-config.json")
    if (not smoke["passed"] or not smoke["storedFileDownloadMatches"] or not smoke["latestVectorIdsOnly"]
            or not smoke["ownDocumentAndVectorsDeleted"] or not smoke["originalPublicIndexUnchanged"]
            or smoke["vectorDimensions"] != 1024 or not smoke_runtime["durableEnabled"]):
        raise ValueError("Actual latest-candidate durable-enabled HTTP smoke missing")
    smoke_hashes = read(RUN / "api-smoke/source-hashes.json")
    if any(digest(ROOT / path) != expected for path, expected in smoke_hashes.items()):
        raise ValueError("Latest actual API sources changed")
    controls.update(smoke_hashes)
    sources = [ROOT / path for path in controls]
    sources.extend([Path(__file__), Path(__file__).parent / "run-fault-tests.ps1",
                    Path(__file__).parent / "collect_execution_lease.py", Path(__file__).parent / "collect_retry_crash.py",
                    Path(__file__).parent / "collect_status_delete.py", Path(__file__).parent / "finalize_vector_generation.py",
                    ROOT / "docker/postgres/migrations/README.md",
                    RUN / "regression-batch.ps1"])
    sources = list(dict.fromkeys(sources))
    tests = sorted((ROOT / "app/build/test-results/test").glob("TEST-*.xml"))
    app_java = [p for p in sources if p.suffix == ".java" and p.is_relative_to(ROOT / "app/src")]
    if not tests or min(p.stat().st_mtime for p in tests) < max(p.stat().st_mtime for p in app_java):
        raise ValueError("Required default backend regression is not fresh")
    default = {k: sum(int(ET.parse(p).getroot().get(k, 0)) for p in tests)
               for k in ("tests", "failures", "errors", "skipped")}
    default["suites"] = len(tests)
    if default["tests"] != 303 or any(default[k] for k in ("failures", "errors", "skipped")):
        raise ValueError("Default regression failed")
    cleanup = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-X", "-A", "-t", "-c", """
      SELECT json_build_object(
        'fixtureDocuments',(SELECT count(*) FROM knowledge_bases WHERE category IN ('async-fault-20261001','async-generation-20261001','generation-smoke-20261001','durable-restart-smoke-20261002')),
        'taskRows',(SELECT count(*) FROM kb_vector_tasks),
        'temporaryVectors',(SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' IS NOT NULL),
        'fixtureTriggers',(SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'rehevo_fault_%' OR tgname LIKE 'rehevo_delivery_%' OR tgname LIKE 'rehevo_execution_%'),
        'fixtureFunctions',(SELECT count(*) FROM pg_proc WHERE proname LIKE 'rehevo_fault_%' OR proname LIKE 'rehevo_delivery_%' OR proname LIKE 'rehevo_execution_%'),
        'fixtureSchemas',(SELECT count(*) FROM pg_namespace WHERE nspname LIKE 'rehevo_generation_%' OR nspname LIKE 'rehevo_delivery_%' OR nspname LIKE 'rehevo_execution_%'));
    """)
    groups = docker_json("redis", "redis-cli", "--json", "XINFO", "GROUPS", "knowledgebase:vectorize:stream")
    decoded = [r if isinstance(r, dict) else dict(zip(r[::2], r[1::2])) for r in groups]
    group = [r for r in decoded if r.get("name") == "vectorize-group"]
    if len(group) != 1:
        raise ValueError("Consumer group missing")
    users = docker_json("redis", "redis-cli", "--json", "ACL", "USERS")
    cleanup.update(pendingMessages=group[0]["pending"], unreadLag=group[0]["lag"],
                   remainingFixtureAclUsers=[u for u in users if u.startswith("rehevo-fault-")])
    permission = docker_json("redis", "redis-cli", "--json", "ACL", "DRYRUN", "default", "XADD",
                             "knowledgebase:vectorize:stream", "*", "kbId", "controlled-permission-probe")
    if cleanup["unreadLag"] is None or any(cleanup.values()) or permission != "OK":
        raise ValueError("Cleanup/original ACL gate failed")
    original = read(ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001/index-backup-manifest.json")
    health = requests.get(BASE_URL + "/actuator/health", timeout=10)
    health.raise_for_status()
    rows = api("/api/knowledgebase/list")
    runtime = checks.runtime_settings()
    if (vectors_hash(current()) != original["vectorsSha256"] or health.json().get("status") != "UP"
            or {r["id"] for r in rows} != {1, 2, 3, 4} or any(r["vectorStatus"] != "COMPLETED" for r in rows)):
        raise ValueError("Original runtime not restored")
    freeze_sources(sources, RUN / "candidate-sources")
    backend_dir = RUN / "backend-tests"
    backend_dir.mkdir(exist_ok=False)
    write_json(backend_dir / "file-index.json", {p.name: f"t{n:03d}.xml" for n, p in enumerate(tests)})
    for n, path in enumerate(tests):
        shutil.copyfile(path, backend_dir / f"t{n:03d}.xml")
    write_json(RUN / "comparison.json", {"scope": "actual JVM exit inside deterministic VectorStore, real PostgreSQL/JPA/Redis, real DB heartbeat rejection; separate real-Embedding latest HTTP smoke",
        "baselineCorrectedPair": baseline, "candidateCorrectedPair": candidate, "sameCorrectedHarnessAndChild": True,
        "productionFilesChangedInPair": production_changes,
        "observations": observations, "regressions": regressions, "defaultBackendRegression": default,
        "latestDurableEnabledProductPath": smoke, "productPathRuntime": smoke_runtime,
        "previousImmutableArtifacts": len(prior_manifest), "previousArtifactChanges": prior_changes,
        "cleanup": cleanup, "defaultDurableEnabled": False, "restoredActualRuntime": runtime,
        "originalPublicIndexUnchanged": True,
        "decision": "retain valid-commit scoped abandoned-temp cleanup; continue config/load/retention/failed-terminal/deletion-compensation gates before default enablement",
        "limits": ["Initial wrong Redisson return enum prevented compilation; no test outcome claimed; corrected attempt retained separately.",
                   "Child fixture persists one temporary batch then actual halt(76); no provider process is interrupted.",
                   "Recovery invokes actual recoverOnce and controls lease/Pending idle; not actual recovery speed.",
                   "Heartbeat rejection is a real owned PostgreSQL trigger error, not total database/network outage or connection-pool stress.",
                   "Temp cleanup occurs only in successful current fenced completion; terminal failures or late writes after cleanup still need retention/reconciliation.",
                   "Latest HTTP smoke does not repeat independent S3 HEAD; previous separately frozen restart smoke performed it on the prior candidate.",
                   "No supplier exactly-once, voice latency or RAG quality claim."]})
    secret_verifier.RUN = RUN
    leaked = secret_verifier.secret_hits()
    if leaked:
        raise ValueError("Sensitive values found; values suppressed")
    manifest = {p.relative_to(RUN).as_posix(): digest(p) for p in sorted(RUN.rglob("*")) if p.is_file()}
    write_json(RUN / "artifacts.sha256.json", manifest)
    changed = [name for name, expected in manifest.items() if digest(RUN / name) != expected]
    index = read(RUN / "candidate-sources/file-index.json")
    source_changes = [p for p, frozen in index.items() if digest(ROOT / p) != digest(RUN / "candidate-sources" / frozen)]
    if changed or source_changes:
        raise ValueError("Closed evidence or current source changed during freeze")
    write_json(RUN / "post-freeze-verification.json", {"frozenArtifacts": len(manifest), "hashMismatches": changed,
        "currentSourcesDifferFromFrozen": source_changes, "sensitiveValueHitFiles": leaked,
        "runtimeHealth": "UP", "originalPublicIndexUnchanged": True, "credentialValuesRecorded": False})
    print(json.dumps({"baselineExecutedFailures": 1, "candidatePasses": 2, "defaultTests": default,
        "frozenArtifacts": len(manifest), "hashMismatches": 0, "sensitiveValueHitFiles": len(leaked), "cleanup": cleanup}, ensure_ascii=False))


if __name__ == "__main__":
    main()
