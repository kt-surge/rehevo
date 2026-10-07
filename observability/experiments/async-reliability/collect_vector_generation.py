"""Freeze generation race A/B, protocol gates, current sources and unchanged original public index."""
import json
import shutil
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "observability/experiments/rag-evaluation"))
from experimental_index_snapshot import current, vectors_hash
from fact_gold import digest
from ingest_primary_dev import docker_json, write_json
from collect_status_delete import xml_summary
from collect_retry_crash import freeze_sources

RUN = Path(__file__).parent / "runs/vector-generation-20261001"


def result(label, tests, failures):
    invocation = json.loads((RUN / f"{label}-invocation.json").read_text(encoding="utf-8-sig"))
    value = xml_summary(RUN / f"{label}.xml")
    if (not invocation["freshIntegrationXmlCaptured"] or invocation["exitCode"] != (1 if failures else 0)
            or value["tests"] != tests or value["failures"] != failures or value["errors"] or value["skipped"]):
        raise ValueError("Required fresh run did not pass/execute: " + label)
    return value


def main():
    if any((RUN / name).exists() for name in ("comparison.json", "candidate-sources", "backend-tests", "artifacts.sha256.json")):
        raise ValueError("Frozen evidence exists; do not overwrite")
    baseline = result("a-race", 2, 2)
    pair = result("b-race-final", 2, 0)
    protocol = result("b-contract-final", 6, 0)
    faults = result("b-faults", 9, 0)
    test_path = "app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorGenerationRaceIntegrationTest.java"
    a_hashes = json.loads((RUN / "a-race-source-hashes.json").read_text(encoding="utf-8-sig"))
    b_hashes = json.loads((RUN / "b-race-final-source-hashes.json").read_text(encoding="utf-8-sig"))
    if a_hashes[test_path] != b_hashes[test_path] or digest(ROOT / test_path) != b_hashes[test_path]:
        raise ValueError("A/B race test source differs")
    controls = {}
    for label in ("b-race-final", "b-contract-final", "b-faults"):
        hashes = json.loads((RUN / f"{label}-source-hashes.json").read_text(encoding="utf-8-sig"))
        if any(digest(ROOT / path) != value for path, value in hashes.items()):
            raise ValueError("Current source differs from " + label)
        controls.update(hashes)
    smoke = json.loads((RUN / "api-smoke/summary.json").read_text(encoding="utf-8"))
    if (not smoke["passed"] or not smoke["storedFileDownloadMatches"]
            or not smoke["latestVectorIdsOnly"] or not smoke["ownDocumentAndVectorsDeleted"]
            or not smoke["originalPublicIndexUnchanged"] or smoke["vectorDimensions"] != 1024
            or smoke["actualUploadAndRevectorizeMessages"] != 2):
        raise ValueError("Actual product entry verification did not pass")
    smoke_hashes = json.loads((RUN / "api-smoke/source-hashes.json").read_text(encoding="utf-8"))
    if any(digest(ROOT / path) != value for path, value in smoke_hashes.items()):
        raise ValueError("Current source differs from the actual API smoke")
    controls.update(smoke_hashes)
    sources = [ROOT / path for path in controls]
    sources.extend([ROOT / "app/src/test/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorServiceTest.java",
                    ROOT / "app/src/test/java/interview/guide/common/async/ConsumerStatePersistenceTest.java",
                    ROOT / "docker/postgres/migrations/20261001_vector_generation.sql",
                    ROOT / "docker/postgres/migrations/20261001_vector_generation_rollback.sql",
                    ROOT / "docker/postgres/migrations/README.md", Path(__file__),
                    Path(__file__).parent / "run-fault-tests.ps1", Path(__file__).parent / "fault-classpath.init.gradle",
                    Path(__file__).parent / "collect_status_delete.py", Path(__file__).parent / "collect_retry_crash.py",
                    Path(__file__).parent / "verify_vector_generation.py"])
    sources = list(dict.fromkeys(sources))
    backend_paths = sorted((ROOT / "app/build/test-results/test").glob("TEST-*.xml"))
    if not backend_paths or min(path.stat().st_mtime for path in backend_paths) < max(path.stat().st_mtime for path in sources if path.suffix == ".java"):
        raise ValueError("Fresh backend regression required")
    default = {key: sum(int(ET.parse(path).getroot().get(key, 0)) for path in backend_paths)
               for key in ("tests", "failures", "errors", "skipped")}
    default["suites"] = len(backend_paths)
    if default["tests"] != 303 or any(default[key] for key in ("failures", "errors", "skipped")):
        raise ValueError("Default regression is not passing")
    original = json.loads((ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001/index-backup-manifest.json").read_text(encoding="utf-8"))
    pending = docker_json("redis", "redis-cli", "--json", "XPENDING", "knowledgebase:vectorize:stream", "vectorize-group")
    users = docker_json("redis", "redis-cli", "--json", "ACL", "USERS")
    cleanup = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-A", "-t", "-c", """
      SELECT json_build_object(
        'fixtureKnowledgeBases',(SELECT count(*) FROM knowledge_bases WHERE category IN ('async-fault-20261001','async-generation-20261001','generation-smoke-20261001')),
        'fixtureTriggers',(SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'rehevo_fault_%'),
        'fixtureSchemas',(SELECT count(*) FROM pg_namespace WHERE nspname LIKE 'rehevo_generation_%'),
        'pendingVectors',(SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' IS NOT NULL));
      """)
    cleanup.update(pendingMessages=pending[0], remainingFixtureAclUsers=[user for user in users if user.startswith("rehevo-fault-")],
                   originalPublicIndexHashMatches=vectors_hash(current()) == original["vectorsSha256"])
    if any(cleanup[key] for key in ("fixtureKnowledgeBases", "fixtureTriggers", "fixtureSchemas", "pendingVectors", "pendingMessages", "remainingFixtureAclUsers")) or not cleanup["originalPublicIndexHashMatches"]:
        raise ValueError("Cleanup/index preservation gate failed: " + json.dumps(cleanup))
    schema = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-A", "-t", "-c", """
      SELECT json_build_object('generationColumnLength',(SELECT character_maximum_length FROM information_schema.columns
        WHERE table_schema='public' AND table_name='knowledge_bases' AND column_name='vector_generation'),
        'originalCompletedLegacyRows',(SELECT count(*) FROM knowledge_bases WHERE id IN(1,2,3,4)
        AND vector_generation IS NULL AND vector_status='COMPLETED'));
      """)
    if schema != {"generationColumnLength": 36, "originalCompletedLegacyRows": 4}:
        raise ValueError("Expected migration compatibility state differs")
    crash = json.loads((RUN / "b-faults-child/recovery.json").read_text(encoding="utf-8"))
    if crash["childExitCode"] != 73 or crash["restartEmbeddingCalls"] or crash["pendingAfterRestart"] or not crash["sameVectorIds"]:
        raise ValueError("Actual crash gate failed")
    freeze_sources(sources, RUN / "candidate-sources")
    backend_dir = RUN / "backend-tests"
    backend_dir.mkdir(exist_ok=False)
    write_json(backend_dir / "file-index.json", {path.name: f"t{number:03d}.xml" for number, path in enumerate(backend_paths)})
    for number, path in enumerate(backend_paths):
        shutil.copyfile(path, backend_dir / f"t{number:03d}.xml")
    write_json(backend_dir / "files.sha256.json", {path.name: digest(path) for path in sorted(backend_dir.iterdir())})
    write_json(RUN / "comparison.json", {"scope": "real isolated PostgreSQL/JPA/Redis; deterministic VectorStore fixture",
        "baselineRacePair": baseline, "candidateSameRacePair": pair, "sameRaceTestSource": True,
        "generationProtocolGroup": protocol, "previousFaultRegression": faults, "actualCrashRecovery": crash,
        "actualProductEntrySmoke": smoke,
        "defaultBackendRegression": default, "schemaCompatibility": schema, "cleanup": cleanup,
        "decision": "retain persisted generation fencing, conditional state writes and atomic promotion/completion",
        "limits": ["Race inputs allocate request markers via actual SQL in both A/B, not a full HTTP revectorize experiment.",
                   "Producer, retry, old-message and migration cases use actual production methods in the separate protocol group.",
                   "No real supplier call in these controlled fault groups.",
                   "Separate API smoke uses real Embedding on one disposable short fixture; not a quality or latency A/B.",
                   "Post-delete storage object absence was not independently verified.",
                   "Fixture idle advancement is not recovery latency.",
                   "No exactly-once model-call or production reliability claim.",
                   "Database/Redis enqueue gap, trim and multiple-worker reclaim still need durable task recovery/lease verification."]})
    write_json(RUN / "artifacts.sha256.json", {path.relative_to(RUN).as_posix(): digest(path) for path in sorted(RUN.rglob("*")) if path.is_file()})
    print(json.dumps({"baselineFailures": 2, "candidateRacePasses": 2, "protocolPasses": 6,
                      "previousFaultPasses": 9, "defaultTests": default, "cleanup": cleanup}, ensure_ascii=False))


if __name__ == "__main__":
    main()
