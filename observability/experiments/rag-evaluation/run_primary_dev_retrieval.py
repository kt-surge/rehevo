"""Evaluate one actual retrieval strategy on frozen primary-dev facts.

The product performs retrieval once per question. Fused candidates and final
context IDs come from that same trace. No generator/judge model is called.
"""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import time
import xml.etree.ElementTree as ET

import requests

from fact_gold import digest, load_gold, load_variant, score
from ingest_primary_dev import provider_snapshot, write_json

ROOT = Path(__file__).resolve().parents[3]
RUN = ROOT / "observability/experiments/rag-evaluation/runs/primary-dev-a-20261001"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("HYBRID", "VECTOR", "HYBRID_CONTEXT", "HYBRID_RERANK"), default="HYBRID")
    parser.add_argument("--label", default="baseline-diagnostic")
    parser.add_argument("--run", type=Path, default=RUN)
    args = parser.parse_args()
    run = args.run.resolve()
    if not all(character.isalnum() or character in "-_" for character in args.label):
        raise ValueError("Use a simple label")
    output = run / args.label
    output.mkdir(exist_ok=False)
    gold = load_gold(ROOT / "data/local/fact-gold-v1-20261001-r2/manifest.json", "agent_verified")
    variant = load_variant(run / "baseline-chunk-variant-r2.json", gold)
    ingestion = json.loads((run / "ingestion.manifest.json").read_text(encoding="utf-8"))
    selected = [row["knowledgeBaseId"] for row in ingestion["documents"]]
    cases = list(gold.cases.values())
    payload = {"queries": [{"knowledgeBaseIds": selected, "question": row["question"]} for row in cases],
               "rewrite": False, "retrievalMode": args.mode}
    write_json(output / "request.json", payload)
    write_json(output / "running-provider-config.json", provider_snapshot())
    started = time.perf_counter()
    response = requests.post("http://127.0.0.1:18080/api/knowledgebase/evaluation/retrieval", json=payload, timeout=120)
    value = response.json()
    write_json(output / "raw-response.json", {"httpStatus": response.status_code,
               "capturedAt": datetime.now(timezone.utc).isoformat(), "batchElapsedMs": (time.perf_counter() - started) * 1000,
               "result": value})
    if response.status_code != 200 or value.get("code") != 200:
        raise ValueError("Batch failed; raw response retained. No scores and no retry in this run.")
    items = value["data"]["items"]
    if len(items) != len(cases):
        raise ValueError("Missing cases in API response; do not silently drop denominator")
    records = []
    for case, item in zip(cases, items):
        if case["question"] != item["question"]:
            raise ValueError("API changed question order")
        for field in ("candidateEvidence", "evidence", "vectorEvidence", "lexicalEvidence"):
            for evidence in item[field]:
                chunk = variant[evidence["vectorDocumentId"]]
                if evidence["documentSha256"] != chunk["actualUploadSha256"]:
                    raise ValueError("Evidence upload fingerprint changed")
        records.append({"caseId": case["id"], "status": "success", "elapsedMs": item["elapsedMs"],
                        "candidateChunkIds": [row["vectorDocumentId"] for row in item["candidateEvidence"]],
                        "contextChunkIds": [row["vectorDocumentId"] for row in item["evidence"]],
                        "vectorChunkIds": [row["vectorDocumentId"] for row in item["vectorEvidence"]],
                        "lexicalChunkIds": [row["vectorDocumentId"] for row in item["lexicalEvidence"]]})
    (output / "records.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in records), encoding="utf-8")
    report = score(gold, variant, records, "dev")
    report.update(mode=args.mode, rewriteRequested=False, corpusChunks=len(variant),
                  latencyScope="single evaluation service call per case, includes trace/evidence/routing; not user generation latency",
                  strategyScope="same model/config/chunks, all four selected KBs; one dev pass, not final test",
                  generatorCalled=False, judgeCalled=False)
    write_json(output / "scores.json", report)
    write_json(output / "effective-retrieval-config.json", value["data"]["configuration"])
    sources = list((ROOT / "app/src/main/java/interview/guide/modules/knowledgebase").rglob("*.java"))
    sources += [ROOT / "app/src/main/resources/application.yml", ROOT / "observability/experiments/runtime/boot-run.ps1",
                Path(__file__), ROOT / "observability/experiments/rag-evaluation/fact_gold.py"]
    write_json(output / "source-hashes.json", {path.relative_to(ROOT).as_posix(): digest(path) for path in sorted(sources)})
    if args.mode == "HYBRID" and (run / "pre-diagnostic-retrieval.json").exists():
        previous = json.loads((run / "pre-diagnostic-retrieval.json").read_text(encoding="utf-8"))["data"]["items"]
        same = [{"question": new["question"], "sameContextIds": [row["vectorDocumentId"] for row in old["evidence"]]
                 == [row["vectorDocumentId"] for row in new["evidence"]],
                 "sameEvidenceMetadata": old["evidence"] == new["evidence"]}
                for old, new in zip(previous, items)]
        write_json(output / "diagnostic-neutrality.json", {"cases": len(same), "rows": same,
                   "identicalContextCases": sum(row["sameContextIds"] for row in same),
                   "identicalEvidenceCases": sum(row["sameEvidenceMetadata"] for row in same)})
        if not all(row["sameContextIds"] for row in same):
            print("CAUTION: context changed after diagnostic instrumentation; investigate before treating as baseline")
    summaries = [ET.parse(path).getroot().attrib for path in (ROOT / "app/build/test-results/test").glob("TEST-*.xml")]
    write_json(output / "backend-test-summary.json", {"suites": len(summaries),
               **{key: sum(int(row.get(key, 0)) for row in summaries) for key in ("tests", "failures", "errors", "skipped")}})
    print(json.dumps({key: report[key] for key in ("mode", "cases", "answerable", "failures",
        "candidateAllRequiredRecall", "contextAllRequiredRecall", "candidateRequirementMicroCoverage",
        "contextRequirementMicroCoverage", "elapsedMedianMs", "elapsedP95Ms")}, ensure_ascii=False))


if __name__ == "__main__":
    main()
