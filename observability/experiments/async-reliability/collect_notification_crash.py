"""Freeze actual post-XADD child exit and actual scheduled-dispatch gates; no concurrency claim."""
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET

import requests

ROOT = Path(__file__).resolve().parents[3]
RUN = Path(__file__).parent / "runs/durable-notification-crash-20261002"
PREVIOUS = Path(__file__).parent / "runs/durable-delivery-20261002"
sys.path.insert(0, str(ROOT / "observability/experiments/rag-evaluation"))
from experimental_index_snapshot import current, vectors_hash
from fact_gold import digest
from ingest_primary_dev import BASE_URL, api, docker_json, write_json
from collect_retry_crash import freeze_sources
from collect_status_delete import xml_summary
import finalize_vector_generation as secret_verifier


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def main():
    if any((RUN / name).exists() for name in ("comparison.json", "candidate-sources", "backend-tests", "artifacts.sha256.json")):
        raise ValueError("Evidence already frozen; do not overwrite")
    invocation = read(RUN / "b-notification-invocation.json")
    result = xml_summary(RUN / "b-notification.xml")
    if (not invocation["freshIntegrationXmlCaptured"] or invocation["exitCode"]
            or result["tests"] != 2 or any(result[key] for key in ("failures", "errors", "skipped"))):
        raise ValueError("Required two fresh notification gates did not pass")
    checkpoint = read(RUN / "b-notification-child/notification-child/checkpoint.json")
    recovered = read(RUN / "b-notification-child/notification-child/recovery.json")
    scheduled = read(RUN / "b-notification-child/scheduled-child/recovery.json")
    if (checkpoint["haltCode"] != 75 or not checkpoint["actualXaddCalled"] or checkpoint["transactionActiveAtXadd"]
            or checkpoint["phase"] != "XADD_SUCCEEDED_BEFORE_RECORD" or recovered["childExitCode"] != 75
            or recovered["physicalNotifications"] != 2 or not recovered["sameGenerationAndContent"]
            or not recovered["singleConsumerOnly"] or recovered["embeddingFixtureCalls"] != 1
            or recovered["pendingAfterRestart"] or recovered["backlogAfterRestart"]
            or not scheduled["actualScheduledAnnotationActivated"] or scheduled["manualRecoverOnceCalled"]
            or scheduled["embeddingFixtureCalls"] != 1 or scheduled["physicalNotifications"] != 1
            or scheduled["status"] != "COMPLETED"):
        raise ValueError("Actual exit/scheduling observations differ")
    hashes = read(RUN / "b-notification-source-hashes.json")
    if any(digest(ROOT / path) != value for path, value in hashes.items()):
        raise ValueError("Invocation source changed")
    prior_sources = read(PREVIOUS / "candidate-sources/file-index.json")
    changed_production = [path for path, frozen in prior_sources.items() if path.startswith("app/src/main/")
                          and digest(ROOT / path) != digest(PREVIOUS / "candidate-sources" / frozen)]
    previous_manifest = read(PREVIOUS / "artifacts.sha256.json")
    changed_evidence = [path for path, value in previous_manifest.items() if digest(PREVIOUS / path) != value]
    if changed_production or changed_evidence:
        raise ValueError("Previous production candidate or frozen evidence changed")
    sources = [ROOT / path for path in hashes]
    sources.extend([Path(__file__), Path(__file__).parent / "run-fault-tests.ps1",
                    Path(__file__).parent / "collect_retry_crash.py",
                    Path(__file__).parent / "collect_status_delete.py",
                    Path(__file__).parent / "finalize_vector_generation.py"])
    sources = list(dict.fromkeys(sources))
    backend_paths = sorted((ROOT / "app/build/test-results/test").glob("TEST-*.xml"))
    if not backend_paths or min(p.stat().st_mtime for p in backend_paths) < max(p.stat().st_mtime for p in sources if p.suffix == ".java"):
        raise ValueError("Fresh default backend regression required")
    default = {key: sum(int(ET.parse(path).getroot().get(key, 0)) for path in backend_paths)
               for key in ("tests", "failures", "errors", "skipped")}
    default["suites"] = len(backend_paths)
    if default["tests"] != 303 or any(default[key] for key in ("failures", "errors", "skipped")):
        raise ValueError("Default backend regression did not pass")
    cleanup = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-X", "-A", "-t", "-c", """
      SELECT json_build_object(
        'fixtureDocuments',(SELECT count(*) FROM knowledge_bases WHERE category='async-fault-20261001'),
        'taskRows',(SELECT count(*) FROM kb_vector_tasks),
        'temporaryVectors',(SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' IS NOT NULL),
        'fixtureTriggers',(SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'rehevo_fault_%' OR tgname LIKE 'rehevo_delivery_%'),
        'fixtureSchemas',(SELECT count(*) FROM pg_namespace WHERE nspname LIKE 'rehevo_generation_%' OR nspname LIKE 'rehevo_delivery_%'));
      """)
    groups = docker_json("redis", "redis-cli", "--json", "XINFO", "GROUPS", "knowledgebase:vectorize:stream")
    decoded = [row if isinstance(row, dict) else dict(zip(row[::2], row[1::2])) for row in groups]
    group = [row for row in decoded if row.get("name") == "vectorize-group"]
    if len(group) != 1:
        raise ValueError("Missing unique actual consumer group")
    cleanup.update(pendingMessages=group[0]["pending"], unreadLag=group[0]["lag"])
    if cleanup["unreadLag"] is None or any(cleanup.values()):
        raise ValueError("Fixture cleanup/queue idle gate failed")
    original = read(ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001/index-backup-manifest.json")
    index_unchanged = vectors_hash(current()) == original["vectorsSha256"]
    health = requests.get(BASE_URL + "/actuator/health", timeout=10)
    health.raise_for_status()
    rows = api("/api/knowledgebase/list")
    if (not index_unchanged or health.json().get("status") != "UP" or {row["id"] for row in rows} != {1, 2, 3, 4}
            or any(row["vectorStatus"] != "COMPLETED" for row in rows)):
        raise ValueError("Original runtime/index not restored")
    freeze_sources(sources, RUN / "candidate-sources")
    directory = RUN / "backend-tests"
    directory.mkdir(exist_ok=False)
    write_json(directory / "file-index.json", {p.name: f"t{n:03d}.xml" for n, p in enumerate(backend_paths)})
    for number, path in enumerate(backend_paths):
        shutil.copyfile(path, directory / f"t{number:03d}.xml")
    write_json(RUN / "comparison.json", {"scope": "real isolated PostgreSQL/JPA/Redis + actual JVM exit + actual Spring Scheduled; deterministic VectorStore",
        "gates": result, "checkpoint": checkpoint, "recovered": recovered, "scheduled": scheduled,
        "defaultBackendRegression": default, "previousProductionSourceChanges": changed_production,
        "previousFrozenEvidenceChanges": changed_evidence, "cleanup": cleanup, "runtimeHealth": health.json(),
        "originalPublicIndexUnchanged": index_unchanged, "defaultDurableEnabled": False,
        "decision": "retain notification gates; proceed to multi-worker execution fencing and persistent budget",
        "limits": ["Post-XADD recovery deliberately invokes actual recoverOnce, then processes duplicates with one consumer.",
                   "Scheduled gate activates actual annotation in a controlled Spring context with 100ms scan; not full application restart.",
                   "No actual provider call, supplier exactly-once, concurrency guarantee or recovery latency result.",
                   "Previous API compatibility evidence runs durable-disabled and remains preserved separately."]})
    secret_verifier.RUN = RUN
    leaked = secret_verifier.secret_hits()
    if leaked:
        raise ValueError("Sensitive values present; values suppressed")
    manifest = {p.relative_to(RUN).as_posix(): digest(p) for p in sorted(RUN.rglob("*")) if p.is_file()}
    write_json(RUN / "artifacts.sha256.json", manifest)
    mismatches = [p for p, value in manifest.items() if digest(RUN / p) != value]
    if mismatches:
        raise ValueError("Closed artifact changed")
    write_json(RUN / "post-freeze-verification.json", {"frozenArtifacts": len(manifest), "hashMismatches": mismatches,
        "sourceChanges": [], "sensitiveValueHitFiles": leaked, "runtimeHealth": "UP", "originalPublicIndexUnchanged": True})
    print(json.dumps({"notificationPasses": 2, "defaultTests": default, "frozenArtifacts": len(manifest),
                      "hashMismatches": 0, "sensitiveValueHitFiles": len(leaked), "cleanup": cleanup}, ensure_ascii=False))


if __name__ == "__main__":
    main()
