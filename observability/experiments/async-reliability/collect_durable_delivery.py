"""Freeze the corrected same-harness recovery A/B and bounded durable-delivery gates.

Run without redirecting live stdout into RUN: the manifest hashes only closed evidence files.
Manual recoverOnce invocation is not scheduled restart or recovery latency evidence.
"""
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET

import requests

ROOT = Path(__file__).resolve().parents[3]
RUN = Path(__file__).parent / "runs/durable-delivery-20261002"
sys.path.insert(0, str(ROOT / "observability/experiments/rag-evaluation"))
from experimental_index_snapshot import current, vectors_hash
from fact_gold import digest
from ingest_primary_dev import BASE_URL, api, docker_json, write_json
from collect_retry_crash import freeze_sources
from collect_status_delete import xml_summary
import finalize_vector_generation as secret_verifier


def read(name):
    return json.loads((RUN / name).read_text(encoding="utf-8-sig"))


def result(label, tests, failures):
    invocation = read(f"{label}-invocation.json")
    value = xml_summary(RUN / f"{label}.xml")
    if (not invocation["freshIntegrationXmlCaptured"] or invocation["exitCode"] != (1 if failures else 0)
            or value["tests"] != tests or value["failures"] != failures or value["errors"] or value["skipped"]):
        raise ValueError("Missing executed fresh result: " + label)
    return value


def main():
    if any((RUN / name).exists() for name in ("comparison.json", "candidate-sources", "backend-tests", "artifacts.sha256.json")):
        raise ValueError("Evidence already frozen; do not overwrite")
    baseline = result("a-recovery-r1", 2, 2)
    candidate = result("b-recovery-final", 2, 0)
    groups = {label: result(label, count, 0) for label, count in
              (("b-contract-final", 8), ("b-faults", 9), ("b-generation-contract", 6), ("b-race", 2))}
    harness = "app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorTaskDeliveryRecoveryIntegrationTest.java"
    child = "app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorTaskEnqueueCrashWorker.java"
    a_hashes = read("a-recovery-r1-source-hashes.json")
    b_hashes = read("b-recovery-final-source-hashes.json")
    for path in (harness, child):
        if a_hashes[path] != b_hashes[path] or digest(ROOT / path) != b_hashes[path]:
            raise ValueError("Corrected A/B harness or child differs")
    controls = {}
    for label in ("b-recovery-final", *groups):
        hashes = read(f"{label}-source-hashes.json")
        if any(digest(ROOT / path) != value for path, value in hashes.items()):
            raise ValueError("Current source differs from " + label)
        controls.update(hashes)
    observations = {}
    for label in ("a-recovery-r1", "b-recovery-final"):
        enqueue = read(f"{label}-child/enqueue-child/recovery.json")
        checkpoint = read(f"{label}-child/enqueue-child/checkpoint.json")
        trim = read(f"{label}-child/trim-child/recovery.json")
        if (enqueue["childExitCode"] != 74 or checkpoint["actualXaddCalled"]
                or checkpoint["transactionActiveAtXadd"] or enqueue["pendingBeforeRestart"]
                or enqueue["ownMessagesBeforeRestart"] or not trim["originalBodyTrimmed"]
                or not trim["deletedIdReturnedByActualClaim"]):
            raise ValueError("Actual fault checkpoints absent")
        expected_status = "PENDING" if label.startswith("a-") else "COMPLETED"
        expected_calls = 0 if label.startswith("a-") else 1
        if (enqueue["statusAfterRestart"] != expected_status or trim["statusAfterRecovery"] != expected_status
                or enqueue["embeddingFixtureCalls"] != expected_calls or trim["embeddingFixtureCalls"] != expected_calls):
            raise ValueError("Recovery observations differ from required result")
        observations[label] = {"enqueue": enqueue, "checkpoint": checkpoint, "trim": trim}
    smoke = read("api-smoke/summary.json")
    if (not smoke["passed"] or not smoke["storedFileDownloadMatches"] or not smoke["latestVectorIdsOnly"]
            or not smoke["ownDocumentAndVectorsDeleted"] or not smoke["originalPublicIndexUnchanged"]
            or smoke["vectorDimensions"] != 1024 or smoke["actualUploadAndRevectorizeMessages"] != 2):
        raise ValueError("Actual compatibility product path failed")
    smoke_hashes = read("api-smoke/source-hashes.json")
    if any(digest(ROOT / path) != value for path, value in smoke_hashes.items()):
        raise ValueError("Actual product path source changed")
    controls.update(smoke_hashes)
    sources = [ROOT / path for path in controls]
    sources.extend([ROOT / "docker/postgres/migrations/20261002_vector_tasks.sql",
                    ROOT / "docker/postgres/migrations/20261002_vector_tasks_rollback.sql",
                    ROOT / "docker/postgres/migrations/README.md", Path(__file__),
                    Path(__file__).parent / "prepare_durable_baseline.py",
                    Path(__file__).parent / "run-fault-tests.ps1",
                    Path(__file__).parent / "collect_retry_crash.py",
                    Path(__file__).parent / "collect_status_delete.py",
                    Path(__file__).parent / "finalize_vector_generation.py"])
    sources = list(dict.fromkeys(sources))
    backend_paths = sorted((ROOT / "app/build/test-results/test").glob("TEST-*.xml"))
    if not backend_paths or min(path.stat().st_mtime for path in backend_paths) < max(path.stat().st_mtime for path in sources if path.suffix == ".java"):
        raise ValueError("Default regression must be fresh after all Java changes")
    default = {key: sum(int(ET.parse(path).getroot().get(key, 0)) for path in backend_paths)
               for key in ("tests", "failures", "errors", "skipped")}
    default["suites"] = len(backend_paths)
    if default["tests"] != 303 or any(default[key] for key in ("failures", "errors", "skipped")):
        raise ValueError("Default backend regression failed")
    cleanup = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-X", "-A", "-t", "-c", """
      SELECT json_build_object(
        'fixtureKnowledgeBases',(SELECT count(*) FROM knowledge_bases WHERE category IN ('async-fault-20261001','async-generation-20261001','generation-smoke-20261001')),
        'taskRows',(SELECT count(*) FROM kb_vector_tasks),
        'fixtureTriggers',(SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'rehevo_fault_%' OR tgname LIKE 'rehevo_delivery_%'),
        'fixtureSchemas',(SELECT count(*) FROM pg_namespace WHERE nspname LIKE 'rehevo_generation_%' OR nspname LIKE 'rehevo_delivery_%'),
        'pendingVectors',(SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' IS NOT NULL));
      """)
    pending = docker_json("redis", "redis-cli", "--json", "XPENDING", "knowledgebase:vectorize:stream", "vectorize-group")
    users = docker_json("redis", "redis-cli", "--json", "ACL", "USERS")
    cleanup.update(pendingMessages=pending[0], remainingFixtureAclUsers=[u for u in users if u.startswith("rehevo-fault-")])
    original = json.loads((ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001/index-backup-manifest.json").read_text(encoding="utf-8"))
    index_unchanged = vectors_hash(current()) == original["vectorsSha256"]
    if any(cleanup.values()) or not index_unchanged:
        raise ValueError("Owned fixture cleanup or original index preservation failed")
    health = requests.get(BASE_URL + "/actuator/health", timeout=10)
    health.raise_for_status()
    rows = api("/api/knowledgebase/list")
    if (health.json().get("status") != "UP" or {row["id"] for row in rows} != {1, 2, 3, 4}
            or any(row["vectorStatus"] != "COMPLETED" for row in rows)):
        raise ValueError("Original runtime was not restored")
    write_json(RUN / "control-inputs-v2.json", {**read("control-inputs.json"),
        "restartObservationSeconds": 12, "testOnlyRedeliveryDelayMs": 1000, "productionDefaultRedeliveryDelayMs": 30000,
        "correctedBaselineLabel": "a-recovery-r1", "finalCandidateLabel": "b-recovery-final",
        "sameCorrectedRecoveryHarnessSha256": b_hashes[harness], "actualSchedulingTested": False,
        "recoveryTrigger": "test invokes actual VectorTaskRecoveryService.recoverOnce after actual consumer startup",
        "recoveryLatencyClaim": False, "compatibilityApiDurableEnabled": False})
    freeze_sources(sources, RUN / "candidate-sources")
    backend_dir = RUN / "backend-tests"
    backend_dir.mkdir(exist_ok=False)
    write_json(backend_dir / "file-index.json", {p.name: f"t{n:03d}.xml" for n, p in enumerate(backend_paths)})
    for number, path in enumerate(backend_paths):
        shutil.copyfile(path, backend_dir / f"t{number:03d}.xml")
    write_json(RUN / "comparison.json", {"scope": "real isolated PostgreSQL/JPA/Redis/child JVM; deterministic VectorStore",
        "baselineSameRecoveryPair": baseline, "candidateSameRecoveryPair": candidate, "sameHarnessAndChild": True,
        "observations": observations, "protocolAndPreviousRegressions": groups, "defaultBackendRegression": default,
        "actualCompatibilityProductPath": smoke, "cleanup": cleanup, "originalPublicIndexUnchanged": index_unchanged,
        "runtimeHealth": health.json(), "originalKnowledgeBases": rows,
        "decision": "retain candidate persisted input and atomic acceptance; keep default disabled pending execution gates",
        "limits": ["Recovery uses actual recoverOnce explicitly; not actual scheduled restart or latency evidence.",
                   "Corrected observation window 12s and fixture redelivery 1s are identical in A/B; production default 30s.",
                   "Initial A/B attempts including candidate failures remain preserved; first attempts were not final evidence.",
                   "Delivery leases verified with controlled DB time advancement; no execution lease or persistent business budget yet.",
                   "Compatibility API smoke runs durable-disabled; not durable production rollout evidence.",
                   "No exactly-once external model call, RAG quality, voice latency or production reliability claim."]})
    secret_verifier.RUN = RUN
    leaked = secret_verifier.secret_hits()
    if leaked:
        raise ValueError("Sensitive values found in evidence; values suppressed")
    manifest = {path.relative_to(RUN).as_posix(): digest(path) for path in sorted(RUN.rglob("*")) if path.is_file()}
    write_json(RUN / "artifacts.sha256.json", manifest)
    changed = [name for name, value in manifest.items() if digest(RUN / name) != value]
    source_index = read("candidate-sources/file-index.json")
    source_changes = [name for name, frozen in source_index.items() if digest(ROOT / name) != digest(RUN / "candidate-sources" / frozen)]
    if changed or source_changes:
        raise ValueError("Closed evidence/source changed during freeze")
    write_json(RUN / "post-freeze-verification.json", {"frozenArtifacts": len(manifest), "hashMismatches": changed,
        "currentSourcesDifferFromFrozen": source_changes, "sensitiveValueHitFiles": leaked,
        "credentialValuesRecorded": False, "runtimeHealth": "UP", "originalPublicIndexUnchanged": index_unchanged})
    print(json.dumps({"baselineFailures": 2, "candidatePasses": 2, "protocolPasses": 8,
                      "defaultTests": default, "frozenArtifacts": len(manifest), "hashMismatches": len(changed),
                      "sourceChanges": len(source_changes), "sensitiveValueHitFiles": len(leaked)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
