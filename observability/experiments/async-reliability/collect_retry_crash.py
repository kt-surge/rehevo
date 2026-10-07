"""Freeze actual retry-outcome A/B and child-JVM crash evidence without provider calls."""
import argparse
import json
import re
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

RUN = Path(__file__).parent / "runs/retry-crash-20261001"


def fresh_result(label, expected_tests, expected_failures):
    invocation = json.loads((RUN / f"{label}-invocation.json").read_text(encoding="utf-8-sig"))
    if not invocation["freshIntegrationXmlCaptured"]:
        raise ValueError("No fresh XML for " + label)
    expected_exit = 0 if expected_failures == 0 else 1
    if invocation["exitCode"] != expected_exit:
        raise ValueError("Unexpected invocation exit for " + label)
    result = xml_summary(RUN / f"{label}.xml")
    if result["tests"] != expected_tests or result["failures"] != expected_failures or result["errors"] or result["skipped"]:
        raise ValueError("Unexpected executed/passed cases: " + json.dumps(result))
    return result


def retry_metrics(result):
    rows = [row for row in result["observations"] if row.startswith("FAULT_RETRY_METRICS ")]
    if len(rows) != 1:
        raise ValueError("Missing or ambiguous actual retry metric observation")
    return {key: float(value) for key, value in re.findall(r"(retry|failure)=([0-9.]+)", rows[0])}


def freeze_sources(paths, directory):
    directory.mkdir(exist_ok=False)
    index = {}
    for number, path in enumerate(paths):
        target = f"s{number:02d}{path.suffix}"
        shutil.copyfile(path, directory / target)
        index[path.relative_to(ROOT).as_posix()] = target
    write_json(directory / "file-index.json", index)
    write_json(directory / "files.sha256.json", {path.name: digest(path) for path in sorted(directory.iterdir())})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", default="b-final-r1")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z][a-z0-9-]{0,40}", args.label):
        raise ValueError("Unsafe run label")
    if any((RUN / name).exists() for name in ("comparison.json", "candidate-sources", "backend-tests", "artifacts.sha256.json")):
        raise ValueError("Frozen evidence exists; do not overwrite")
    baseline = fresh_result("a-retry-metrics", 1, 1)
    same_pair = fresh_result("b-retry-metrics", 1, 0)
    final = fresh_result(args.label, 9, 0)
    if retry_metrics(baseline) != {"retry": 1.0, "failure": 0.0} or retry_metrics(same_pair) != {"retry": 0.0, "failure": 1.0}:
        raise ValueError("A/B metric evidence is not the expected actual failure")
    crash = json.loads((RUN / f"{args.label}-child/recovery.json").read_text(encoding="utf-8"))
    checkpoint = json.loads((RUN / f"{args.label}-child/checkpoint.json").read_text(encoding="utf-8"))
    if (crash["childExitCode"] != 73 or not crash["actualChildExited"] or crash["pendingBeforeRestart"] != 1
            or crash["pendingAfterRestart"] != 0 or crash["restartEmbeddingCalls"] != 0
            or not crash["sameVectorIds"] or checkpoint["actualXackCalled"] or checkpoint["fixtureCalls"] != 1):
        raise ValueError("Actual process exit / pending / business effect was not verified")
    if not any(row.startswith("FAULT_DOUBLE_FAILURE_RECOVER ") for row in final["observations"]):
        raise ValueError("Double-failure recovery was not executed")
    hashes = json.loads((RUN / f"{args.label}-source-hashes.json").read_text(encoding="utf-8-sig"))
    if any(digest(ROOT / path) != expected for path, expected in hashes.items()):
        raise ValueError("Java sources changed after the final fault invocation")
    sources = [ROOT / path for path in hashes]
    sources.extend([ROOT / "app/src/test/java/interview/guide/common/async/ConsumerStatePersistenceTest.java",
                    ROOT / "METRICS_CONTRACT.md", Path(__file__), Path(__file__).parent / "run-fault-tests.ps1",
                    Path(__file__).parent / "fault-classpath.init.gradle"])
    backend_paths = sorted((ROOT / "app/build/test-results/test").glob("TEST-*.xml"))
    latest_java = max(path.stat().st_mtime for path in sources if path.suffix == ".java")
    if not backend_paths or min(path.stat().st_mtime for path in backend_paths) < latest_java:
        raise ValueError("Default backend XML predates current Java source")
    backend = [ET.parse(path).getroot().attrib for path in backend_paths]
    default = {"suites": len(backend), **{key: sum(int(row.get(key, 0)) for row in backend)
               for key in ("tests", "failures", "errors", "skipped")}}
    if default["tests"] != 303 or any(default[key] for key in ("failures", "errors", "skipped")):
        raise ValueError("Required fresh default regression not passing: " + json.dumps(default))
    original = json.loads((ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001/index-backup-manifest.json")
                          .read_text(encoding="utf-8"))
    pending = docker_json("redis", "redis-cli", "--json", "XPENDING", "knowledgebase:vectorize:stream", "vectorize-group")
    users = docker_json("redis", "redis-cli", "--json", "ACL", "USERS")
    cleanup = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-A", "-t", "-c", """
      SELECT json_build_object(
        'fixtureKnowledgeBases',(SELECT count(*) FROM knowledge_bases WHERE category = 'async-fault-20261001'),
        'fixtureTriggers',(SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'rehevo_fault_%'),
        'pendingVectors',(SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' IS NOT NULL));
      """)
    cleanup.update(pendingMessages=pending[0], remainingFixtureAclUsers=[user for user in users if user.startswith("rehevo-fault-")],
                   originalPublicIndexHashMatches=vectors_hash(current()) == original["vectorsSha256"])
    if any(cleanup[key] for key in ("fixtureKnowledgeBases", "fixtureTriggers", "pendingVectors", "pendingMessages", "remainingFixtureAclUsers")):
        raise ValueError("Fault cleanup incomplete: " + json.dumps(cleanup))
    if not cleanup["originalPublicIndexHashMatches"]:
        raise ValueError("Original four-document index changed")
    # All gates are checked before creating the frozen outputs.
    freeze_sources(sources, RUN / "candidate-sources")
    backend_dir = RUN / "backend-tests"
    backend_dir.mkdir(exist_ok=False)
    write_json(backend_dir / "file-index.json", {path.name: f"t{number:03d}.xml" for number, path in enumerate(backend_paths)})
    for number, path in enumerate(backend_paths):
        shutil.copyfile(path, backend_dir / f"t{number:03d}.xml")
    write_json(backend_dir / "files.sha256.json", {path.name: digest(path) for path in sorted(backend_dir.iterdir())})
    write_json(RUN / "comparison.json", {
        "scope": "real isolated PostgreSQL/JPA/Redis; deterministic VectorStore fixture; actual separately launched child JVM halt",
        "baselineMetricCase": baseline, "candidateSameMetricCase": same_pair,
        "actualMetricComparison": {"A": retry_metrics(baseline), "B": retry_metrics(same_pair)},
        "finalFaultGroup": final, "crashRecovery": crash, "defaultBackendRegression": default, "cleanup": cleanup,
        "decision": "retain truthful retry outcomes and propagated state failures; completed-before-ACK crash recovery verified",
        "stillRequired": ["obsolete-job generation isolation", "multiple active workers / premature pending reclamation",
                          "stream trimming or durable backlog recovery", "full product paths", "remaining voice/RAG goals"],
        "limits": ["Controlled idle advancement does not measure five-minute recovery latency.",
                   "Crash checkpoint is COMPLETED before XACK, not every process-crash phase.",
                   "Three additional consumer regressions use fixture repositories/transport, not real database model paths.",
                   "No actual supplier call, production reliability percentage, or exactly-once guarantee."]})
    write_json(RUN / "artifacts.sha256.json", {path.relative_to(RUN).as_posix(): digest(path)
               for path in sorted(RUN.rglob("*")) if path.is_file()})
    print(json.dumps({"metricComparison": {"A": retry_metrics(baseline), "B": retry_metrics(same_pair)},
                      "finalFaultTests": final["tests"], "defaultTests": default, "cleanup": cleanup}, ensure_ascii=False))


if __name__ == "__main__":
    main()
