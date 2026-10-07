"""Freeze controlled real-infrastructure fault evidence; never equate it with model performance."""
from pathlib import Path
import argparse
import json
import shutil
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
RAG_TOOLS = ROOT / "observability/experiments/rag-evaluation"
sys.path.insert(0, str(RAG_TOOLS))
from experimental_index_snapshot import current, vectors_hash
from fact_gold import digest
from ingest_primary_dev import docker_json, write_json

RUN = Path(__file__).parent / "runs/status-delete-20261001"


def xml_summary(path):
    value = ET.parse(path).getroot()
    return {"path": path.name, **{key: int(value.attrib[key]) for key in ("tests", "failures", "errors", "skipped")},
            "cases": [{"name": row.attrib["name"], "passed": not row.findall("failure") and not row.findall("skipped"),
                       "failures": [failure.attrib.get("message", "") for failure in row.findall("failure")]}
                      for row in value.findall("testcase")],
            "observations": [line for line in (value.findtext("system-out") or "").splitlines()
                             if line.startswith("FAULT_")]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--label", default="b-final-r2")
    args = parser.parse_args()
    if not all(character.isalnum() or character == "-" for character in args.label):
        raise ValueError("Use a simple frozen run label")
    target = RUN / "comparison.json"
    if target.exists():
        raise ValueError("Fault evidence exists; do not overwrite")
    invocation = json.loads((RUN / f"{args.label}-invocation.json").read_text(encoding="utf-8-sig"))
    if not invocation["freshIntegrationXmlCaptured"] or invocation["exitCode"] != 0:
        raise ValueError("Final fault run has no fresh passing XML")
    baseline = xml_summary(RUN / "a-test-r1.xml")
    same_pair = xml_summary(RUN / "b-test.xml")
    final = xml_summary(RUN / f"{args.label}.xml")
    if baseline["tests"] != 2 or baseline["failures"] != 2 or same_pair["tests"] != 2 or same_pair["failures"] != 0:
        raise ValueError("A/B pair changed; inspect original evidence")
    if final["tests"] != 7 or any(final[key] for key in ("failures", "errors", "skipped")):
        raise ValueError("Required final fault cases did not all execute/pass")
    state = current()
    original = json.loads((ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001/index-backup-manifest.json")
                          .read_text(encoding="utf-8"))
    pending = docker_json("redis", "redis-cli", "--json", "XPENDING", "knowledgebase:vectorize:stream", "vectorize-group")
    users = docker_json("redis", "redis-cli", "--json", "ACL", "USERS")
    pg_state = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-A", "-t", "-c", """
      SELECT json_build_object(
        'fixtureKnowledgeBases',(SELECT count(*) FROM knowledge_bases WHERE category = 'async-fault-20261001'),
        'fixtureTriggers',(SELECT count(*) FROM pg_trigger WHERE tgname LIKE 'rehevo_fault_%'),
        'pendingVectors',(SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' IS NOT NULL));
      """)
    cleanup = {**pg_state, "pendingMessages": pending[0],
               "remainingFixtureAclUsers": [user for user in users if user.startswith("rehevo-fault-")],
               "originalPublicIndexHashMatches": vectors_hash(state) == original["vectorsSha256"]}
    if any(cleanup[key] for key in ("fixtureKnowledgeBases", "fixtureTriggers", "pendingVectors", "pendingMessages",
                                   "remainingFixtureAclUsers")) or not cleanup["originalPublicIndexHashMatches"]:
        raise ValueError("Fault cleanup or original index preservation failed: " + json.dumps(cleanup))
    snapshot = RUN / "candidate-sources"
    snapshot.mkdir(exist_ok=False)
    files = [ROOT / "app/src/main/java/interview/guide/common/async/AbstractStreamConsumer.java",
             ROOT / "app/src/main/java/interview/guide/modules/knowledgebase/listener/VectorizeStreamConsumer.java",
             ROOT / "app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java",
             ROOT / "app/src/main/java/interview/guide/modules/knowledgebase/repository/VectorRepository.java",
             ROOT / "app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorizeFaultIntegrationTest.java",
             ROOT / "app/src/test/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorServiceTest.java",
             ROOT / "app/src/main/java/interview/guide/common/transaction/TransactionalExecutor.java",
             Path(__file__), Path(__file__).parent / "run-fault-tests.ps1"]
    latest_business_source = max(path.stat().st_mtime for path in files if path.suffix == ".java")
    backend_paths = sorted((ROOT / "app/build/test-results/test").glob("TEST-*.xml"))
    if not backend_paths or min(path.stat().st_mtime for path in backend_paths) < latest_business_source:
        raise ValueError("Backend XML predates the current Java source; fresh regression required")
    index = {}
    for number, path in enumerate(files):
        name = f"s{number:02d}{path.suffix}"
        shutil.copyfile(path, snapshot / name)
        index[path.relative_to(ROOT).as_posix()] = name
    write_json(snapshot / "file-index.json", index)
    write_json(snapshot / "files.sha256.json", {path.name: digest(path) for path in sorted(snapshot.iterdir()) if path.is_file()})
    backend = [ET.parse(path).getroot().attrib for path in backend_paths]
    default_tests = {"suites": len(backend), **{key: sum(int(row.get(key, 0)) for row in backend)
                    for key in ("tests", "failures", "errors", "skipped")}}
    if not backend or any(default_tests[key] for key in ("failures", "errors", "skipped")):
        raise ValueError("Default backend regression is not passing")
    test_directory = RUN / "backend-tests"
    test_directory.mkdir(exist_ok=False)
    write_json(test_directory / "file-index.json", {path.name: f"t{number:03d}.xml"
               for number, path in enumerate(backend_paths)})
    for number, path in enumerate(backend_paths):
        shutil.copyfile(path, test_directory / f"t{number:03d}.xml")
    write_json(test_directory / "files.sha256.json", {path.name: digest(path)
               for path in sorted(test_directory.iterdir()) if path.is_file()})
    write_json(target, {"scope": "real isolated PostgreSQL/JPA/Redis, deterministic VectorStore fixtures; no provider/model call",
               "baselinePair": baseline, "candidateSamePair": same_pair, "finalFaultGroup": final,
               "defaultBackendRegression": default_tests, "cleanup": cleanup,
               "faultCategoriesVerified": ["duplicate delivery in one active consumer", "timeout claim with fixture-adjusted idle",
                                           "initial/retry enqueue denied by real Redis ACL", "deletion during embedding"],
               "stillRequired": ["actual process crash before ACK", "late obsolete job/version isolation",
                                 "full product paths and remaining S0-S4 work"],
               "caveats": ["Graceful interruption is not a process crash.",
                           "Controlled idle change is not a measured five-minute recovery time.",
                           "No concurrent duplicate delivery/lease claim across multiple active workers was tested.",
                           "Retry enqueue failure still records RETRY in the common task metric; outcome interface needs correction.",
                           "Redis MAXLEN can remove unacknowledged bodies under larger backlog; not tested/solved here."],
               "decision": "retain the two reproduced correctness fixes and verified shutdown interruption; S4 not complete"})
    print(json.dumps({"baseline": baseline["failures"], "samePairCandidateFailures": same_pair["failures"],
                      "finalFaultTests": final["tests"], "defaultTests": default_tests, "cleanup": cleanup}, ensure_ascii=False))


if __name__ == "__main__":
    main()
