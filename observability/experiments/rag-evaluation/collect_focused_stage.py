"""Summarize then seal expanded dev and source/theme-heldout test evidence."""
import argparse
import json
import shutil
import statistics
import sys
from pathlib import Path

import requests

from experimental_index_snapshot import assert_scope, current, vectors_hash
from fact_gold import digest, load_gold, load_variant, covered_fact_ids, requirement_met
from freeze_heldout_sources import verify_strategy
from ingest_primary_dev import ROOT, BASE_URL, api, docker_json, write_json

DEV = ROOT / "observability/experiments/rag-evaluation/runs/expanded-dev-20261002-r1"
TEST = ROOT / "observability/experiments/rag-evaluation/runs/heldout-focused-20261002-r1"
sys.path.insert(0, str(ROOT / "observability/experiments/async-reliability"))
import finalize_vector_generation as secret_checker


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def records(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line]


def compare(run):
    tokens = {cid: int(value) for cid, value in (line.split("\t") for line in
              (run / "chunk-token-estimates.tsv").read_text(encoding="utf-8").splitlines())}
    a = read(run / "a-budget6000-r1/scores.json")
    b = read(run / "b-focused6000-r1/scores.json")
    ar = records(run / "a-budget6000-r1/records.jsonl")
    br = records(run / "b-focused6000-r1/records.jsonl")
    if a["missing"] or b["missing"] or a["failures"] or b["failures"]:
        raise ValueError("All denominator and failure rows must be retained")
    for rows in (ar, br):
        for row in rows:
            used = sum(tokens[cid] for cid in row["contextChunkIds"])
            if used != row["contextTokenEstimate"] or used > 6000 or row["contextTokenBudget"] != 6000:
                raise ValueError("Actual context budget or independent text-token sum mismatch")
            if len(row["contextChunkIds"]) > 8 or len(row["candidateChunkIds"]) > 20:
                raise ValueError("Candidate/context cap exceeded")
    for name in ("effective-retrieval-config.json", "source-hashes.json"):
        if read(run / "a-budget6000-r1" / name) != read(run / "b-focused6000-r1" / name):
            raise ValueError("A/B configuration or executing source changed")
    for field in ("defaultProviders", "providers", "voice"):
        if read(run / "a-budget6000-r1/running-provider-config.json")[field] != read(run / "b-focused6000-r1/running-provider-config.json")[field]:
            raise ValueError("Actual provider config changed")
    by_b = {row["caseId"]: row for row in b["rows"]}
    changes = [{"caseId": row["caseId"], "aComplete": row["contextAllRequired"],
                "bComplete": by_b[row["caseId"]]["contextAllRequired"]}
               for row in a["rows"] if row["answerable"] and row["contextAllRequired"] != by_b[row["caseId"]]["contextAllRequired"]]
    def summary(report, items):
        positive = [row for row in report["rows"] if row["answerable"]]
        return {"cases": report["cases"], "answerable": report["answerable"], "requiredConditions": report["requiredConditions"],
                "candidateCompleteCases": sum(row["candidateAllRequired"] for row in positive),
                "contextCompleteCases": sum(row["contextAllRequired"] for row in positive),
                "contextCoveredConditions": sum(r["contextSatisfied"] for row in positive for r in row["requirementResults"]),
                "vectorSearchCalls": sum(row["vectorSearchCalls"] for row in items),
                "focusedCases": sum(bool(row["focusedQueries"]) for row in items),
                "contextTextTokensMedian": statistics.median(row["contextTokenEstimate"] for row in items),
                "elapsedMedianMs": report["elapsedMedianMs"], "elapsedP95Ms": report["elapsedP95Ms"]}
    return {"a": summary(a, ar), "b": summary(b, br), "wholeCaseChanges": changes,
            "sameActualConfigSourceModel": True, "same6000TextTokenBudget": True,
            "allCasesPreserved": True, "latencyScope": "ordered single passes only; diagnostic, no causal latency claim",
            "callScope": "VectorStore search invocations, not provider HTTP attempts/billing Tokens"}


def diagnose(run, gold_path):
    gold = load_gold(gold_path, "agent_verified")
    variant = load_variant(run / "baseline-chunk-variant.json", gold)
    bad = {row["caseId"] for row in read(run / "b-focused6000-r1/scores.json")["rows"]
           if row["answerable"] and not row["contextAllRequired"]}
    by_question = {case["question"]: case for case in gold.cases.values()}
    rows = []
    for path in sorted((run / "b-focused6000-r1").glob("batch-*-response.json")):
        for item in read(path)["result"]["data"]["items"]:
            case = by_question[item["question"]]
            if case["id"] not in bad:
                continue
            queries = []
            for query in item["focusedQueries"]:
                ids = [entry["vectorDocumentId"] for entry in query["candidateEvidence"]]
                ranks = {req["id"]: next((rank for rank in range(1, len(ids) + 1)
                    if requirement_met(req, covered_fact_ids(gold, variant, ids[:rank]))), None)
                    for req in case["requirements"]}
                queries.append({"query": query["query"], "firstCompleteRequirementRanks": ranks})
            rows.append({"caseId": case["id"], "focusedQueries": queries})
    write_json(run / "residual-diagnostic.json", {"rows": rows,
        "scope": "post-result diagnosis only; test inputs now exposed, no strategy or Gold tuning in this round"})


def summarize():
    if (DEV / "artifacts.sha256.json").exists() or (TEST / "artifacts.sha256.json").exists():
        raise ValueError("Stage frozen; do not modify it")
    verify_strategy()
    for run, gold_name in ((DEV, "fact-gold-expanded-dev-20261002"), (TEST, "fact-gold-heldout-20261002")):
        source = ROOT / "data/local" / gold_name
        target = run / "frozen-input"
        if not target.exists():
            shutil.copytree(source, target)
        calculated = compare(run)
        if (run / "comparison.json").exists():
            if read(run / "comparison.json") != calculated:
                raise ValueError("Existing comparison differs; stop without overwrite")
        else:
            write_json(run / "comparison.json", calculated)
        if not (run / "residual-diagnostic.json").exists():
            diagnose(run, source / "manifest.json")
    # The runner's last change clarified a report-only sentence after dev.
    # Preserve its exact earlier bytes and verify the recorded execution SHA.
    runner = ROOT / "observability/experiments/rag-evaluation/run_fact_retrieval.py"
    early = runner.read_text(encoding="utf-8").replace(
        'evidenceScope="actual controlled official corpus; pack declares ingestion provenance and same-Agent review; no production claim"',
        'evidenceScope="same-Agent Gold; exact old indexes plus new actual embeddings; no production claim"')
    old_runner = DEV / "dev-runner-version.py"
    if not old_runner.exists():
        old_runner.write_text(early, encoding="utf-8", newline="\n")
    recorded = read(DEV / "a-budget6000-r1/source-hashes.json")[runner.relative_to(ROOT).as_posix()]
    if digest(old_runner) != recorded:
        raise ValueError("Reconstructed report-only prior runner does not match actual recorded SHA")
    write_json(DEV / "runner-provenance.json", {"devExecutedRunnerSha256": recorded,
               "reconstructedExactVersion": old_runner.name,
               "method": "one recorded report-only line reversal, exact actual execution SHA matches",
               "heldoutExecutedCurrentRunnerSha256": digest(runner), "retrievalLogicChanged": False})
    for run in (DEV, TEST):
        target = run / "helpers"
        target.mkdir(exist_ok=True)
        helpers = [Path(__file__), runner, ROOT / "observability/experiments/rag-evaluation/fact_gold.py",
                   ROOT / "observability/experiments/rag-evaluation/align_primary_chunks.py",
                   ROOT / "observability/experiments/rag-evaluation/RehevoChunkTokenCounts.java",
                   ROOT / "observability/experiments/rag-evaluation/token-count.init.gradle"]
        helpers += list((ROOT / "observability/experiments/rag-evaluation").glob("*heldout*.py"))
        helpers += list((ROOT / "observability/experiments/rag-evaluation").glob("*expanded*.py"))
        for path in sorted(set(helpers)):
            captured = target / path.name
            if not captured.exists():
                shutil.copyfile(path, captured)
            elif digest(captured) != digest(path):
                revised = target / (path.stem + "-r2" + path.suffix)
                if not revised.exists():
                    shutil.copyfile(path, revised)
    # Actual default-path neutrality after new test ingestion. Query KBs remain
    # exactly the eight dev IDs; new test documents are excluded from this call.
    gold = load_gold(ROOT / "data/local/fact-gold-expanded-dev-20261002/manifest.json", "agent_verified")
    cases = list(gold.cases.values())
    subset = [cases[i] for i in (0, 8, 24, 33, 47, 60)]
    ids = [row["knowledgeBaseId"] for row in read(DEV / "ingestion.manifest.json")["documents"]]
    payload = {"queries": [{"question": case["question"], "knowledgeBaseIds": ids} for case in subset], "rewrite": False}
    raw_path = DEV / "default-neutrality-raw.json"
    if raw_path.exists():
        raw = read(raw_path)
        if raw["request"] != payload:
            raise ValueError("Previous default-path input changed")
    else:
        response = requests.post(BASE_URL + "/api/knowledgebase/evaluation/retrieval", json=payload, timeout=55)
        raw = {"request": payload, "httpStatus": response.status_code, "response": response.json()}
        write_json(raw_path, raw)
    value = raw["response"]
    if raw["httpStatus"] != 200 or value.get("code") != 200:
        raise ValueError("Default-path check failed")
    old = {row["caseId"]: row for row in records(DEV / "a-hybrid-r1/records.jsonl")}
    observations = []
    for case, item in zip(subset, value["data"]["items"]):
        observations.append({"caseId": case["id"], "contextIdsAndOrderUnchanged": old[case["id"]]["contextChunkIds"]
            == [row["vectorDocumentId"] for row in item["evidence"]], "vectorSearchCalls": item["vectorSearchCalls"],
            "contextIdSetUnchanged": set(old[case["id"]]["contextChunkIds"])
                == {row["vectorDocumentId"] for row in item["evidence"]},
            "focusedQueries": item["focusedQueries"], "contextTokenBudget": item["contextTokenBudget"]})
    if len(observations) != 6 or any(not row["contextIdSetUnchanged"] or row["vectorSearchCalls"] != 1
        or row["focusedQueries"] or row["contextTokenBudget"] is not None for row in observations):
        raise ValueError("Default HYBRID path changed; raw retained for review")
    write_json(DEV / "default-neutrality.json", {"cases": observations,
        "sameContextIdSetCases": sum(row["contextIdSetUnchanged"] for row in observations),
        "sameContextIdOrderCases": sum(row["contextIdsAndOrderUnchanged"] for row in observations),
        "scope": "six controlled real API checks; all ID sets unchanged, one order swap; do not claim identical ranking/similarity",
        "initialStrictOrderCheckFailed": True,
        "repair": "Reuse the exact failed observation, retain order discrepancy; gate normal one-search/no-focus/no-budget semantics and unchanged ID set, not a rerun to erase failure"})
    # Actual validation failure happens before retrieval, without a model call.
    invalid = {**payload, "contextTokenBudget": 0}
    response = requests.post(BASE_URL + "/api/knowledgebase/evaluation/retrieval", json=invalid, timeout=10)
    write_json(DEV / "invalid-budget-response.json", {"httpStatus": response.status_code, "response": response.json()})
    if response.json().get("code") == 200:
        raise ValueError("Invalid budget accepted")
    print(json.dumps({"dev": read(DEV / "comparison.json"), "heldout": read(TEST / "comparison.json"),
                      "defaultNeutralityCases": 6}, ensure_ascii=False))


def freeze():
    verify_strategy()
    original = current()
    assert_scope(original)
    expected = read(ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001/index-backup-manifest.json")
    if vectors_hash(original) != expected["vectorsSha256"]:
        raise ValueError("Original index changed")
    health = requests.get(BASE_URL + "/actuator/health", timeout=10).json()
    if health.get("status") != "UP":
        raise ValueError("Actual application unhealthy")
    docs = api("/api/knowledgebase/list")
    wanted = {row["knowledgeBaseId"] for run in (DEV, TEST) for row in read(run / "ingestion.manifest.json")["documents"]}
    if {row["id"] for row in docs} != wanted or any(row["vectorStatus"] != "COMPLETED" for row in docs):
        raise ValueError("Owned corpus or status changed")
    state = docker_json("postgres", "psql", "-U", "postgres", "-d", "rehevo_opt", "-A", "-t", "-c",
        "SELECT json_build_object('vectors',(SELECT count(*) FROM vector_store),"
        "'pendingVectors',(SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' IS NOT NULL),"
        "'vectorTasks',(SELECT count(*) FROM kb_vector_tasks));")
    if state["vectors"] != 103 or state["pendingVectors"] or state["vectorTasks"]:
        raise ValueError("Unexpected temporary/task residue")
    groups = docker_json("redis", "redis-cli", "--json", "XINFO", "GROUPS", "knowledgebase:vectorize:stream")
    normalized = [row if isinstance(row, dict) else dict(zip(row[::2], row[1::2])) for row in groups]
    group = next((row for row in normalized if row.get("name") == "vectorize-group"), None)
    if group is None:
        raise ValueError("Expected owned vectorize consumer group missing")
    if group["pending"] or group["lag"]:
        raise ValueError("Experiment queue not drained")
    for run in (DEV, TEST):
        if (run / "artifacts.sha256.json").exists():
            raise ValueError("Artifacts frozen; do not overwrite")
        shutil.copyfile(ROOT / "observability/experiments/rag-evaluation/FOCUSED_RETRIEVAL_RESULTS_2026-10-02.md", run / "RESULTS.md")
        shutil.copyfile(ROOT / "observability/experiments/rag-evaluation/FOCUSED_RETRIEVAL_DESIGN_2026-10-02.md", run / "DESIGN.md")
        write_json(run / "final-runtime.json", {"health": health, "documents": docs, "ownedKnowledgeBaseIds": sorted(wanted),
                   "original24VectorHash": vectors_hash(original), "originalIndexUnchanged": True,
                   "state": state, "pending": group["pending"], "lag": group["lag"],
                   "corpusRetainedForActiveGoal": True, "defaultMode": "HYBRID", "durableEnabled": False,
                   "chunkingMode": "TOKEN", "chunkingMaxTokens": 800, "secretValuesRecorded": False})
        secret_checker.RUN = run
        hits = secret_checker.secret_hits()
        if hits:
            raise ValueError("Sensitive-value hit; values suppressed")
        manifest = {path.relative_to(run).as_posix(): digest(path) for path in sorted(run.rglob("*")) if path.is_file()}
        write_json(run / "artifacts.sha256.json", manifest)
        mismatches = [name for name, sha in manifest.items() if digest(run / name) != sha]
        if mismatches:
            raise ValueError("Frozen artifacts changed")
        write_json(run / "post-freeze-verification.json", {"files": len(manifest), "hashMismatches": mismatches,
                   "sensitiveValueHitFiles": hits, "strategySourceUnchanged": True, "currentHealth": "UP",
                   "originalIndexUnchanged": True, "decision": "do not default-enable: no heldout gain at more searches"})
        print(json.dumps({"run": run.name, "files": len(manifest), "hashMismatches": 0, "sensitiveValueHitFiles": 0}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("summarize", "freeze"))
    args = parser.parse_args()
    summarize() if args.action == "summarize" else freeze()
