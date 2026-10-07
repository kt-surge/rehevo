"""Collect actual retrieval traces in bounded batches against an explicit frozen Gold.

No generated answers, judge calls, automatic retries, or test-set tuning. Failed
batches remain raw; unattempted cases remain missing in the full denominator.
"""
import argparse
import json
import time
from pathlib import Path

import requests

from fact_gold import digest, load_gold, load_variant, score
from ingest_primary_dev import ROOT, BASE_URL, provider_snapshot, utc_now, write_json


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--variant", default="baseline-chunk-variant.json")
    parser.add_argument("--label", required=True)
    parser.add_argument("--split", choices=("dev", "test"), default="dev")
    parser.add_argument("--mode", default="HYBRID")
    parser.add_argument("--batch-size", type=int, default=5)
    parser.add_argument("--token-budget", type=int)
    args = parser.parse_args()
    if not 1 <= args.batch_size <= 8 or not all(c.isalnum() or c in "-_" for c in args.label):
        raise ValueError("Invalid batch size/label")
    run = args.run.resolve()
    output = run / args.label
    output.mkdir(exist_ok=False)
    gold = load_gold(args.gold, "agent_verified")
    variant = load_variant(run / args.variant, gold)
    ingestion = json.loads((run / "ingestion.manifest.json").read_text(encoding="utf-8"))
    if ingestion["goldManifestSha256"] != digest(gold.manifest_path):
        raise ValueError("Ingestion Gold mismatch")
    selected = [row["knowledgeBaseId"] for row in ingestion["documents"]]
    cases = [case for case in gold.cases.values() if case["split"] == args.split]
    if not cases:
        raise ValueError("No cases for requested split")
    write_json(output / "running-provider-config.json", provider_snapshot())
    write_json(output / "request-plan.json", {"mode": args.mode, "split": args.split,
               "rewrite": False, "selectedKnowledgeBaseIds": selected, "batchSize": args.batch_size,
               "caseIds": [case["id"] for case in cases], "goldSha256": digest(gold.manifest_path),
               "variantSha256": digest(run / args.variant), "contextTokenBudget": args.token_budget})
    paths = list((ROOT / "app/src/main/java/interview/guide/modules/knowledgebase").rglob("*.java"))
    paths += [ROOT / "app/src/main/resources/application.yml", Path(__file__),
              ROOT / "observability/experiments/rag-evaluation/fact_gold.py"]
    write_json(output / "source-hashes.json", {path.relative_to(ROOT).as_posix(): digest(path) for path in sorted(paths)})
    records, configuration = [], None
    for start in range(0, len(cases), args.batch_size):
        batch = cases[start:start + args.batch_size]
        payload = {"queries": [{"knowledgeBaseIds": selected, "question": case["question"]} for case in batch],
                   "rewrite": False, "retrievalMode": args.mode}
        if args.token_budget is not None:
            payload["contextTokenBudget"] = args.token_budget
        write_json(output / f"batch-{start:03d}-request.json", payload)
        began = time.perf_counter()
        try:
            response = requests.post(BASE_URL + "/api/knowledgebase/evaluation/retrieval", json=payload, timeout=(10, 55))
            value = response.json()
            write_json(output / f"batch-{start:03d}-response.json", {"httpStatus": response.status_code,
                       "capturedAt": utc_now(), "batchElapsedMs": (time.perf_counter() - began) * 1000, "result": value})
            if response.status_code != 200 or value.get("code") != 200:
                raise ValueError("Actual batch failed; retained raw response, no retries")
            items = value["data"]["items"]
            if len(items) != len(batch):
                raise ValueError("Missing response cases")
            configuration = value["data"]["configuration"]
            for case, item in zip(batch, items):
                if item["question"] != case["question"]:
                    raise ValueError("Question order changed")
                for field in ("candidateEvidence", "evidence", "vectorEvidence", "lexicalEvidence"):
                    for evidence in item[field]:
                        chunk = variant[evidence["vectorDocumentId"]]
                        if evidence["documentSha256"] != chunk["actualUploadSha256"]:
                            raise ValueError("Actual evidence hash changed")
                records.append({"caseId": case["id"], "status": "success", "elapsedMs": item["elapsedMs"],
                                "candidateChunkIds": [row["vectorDocumentId"] for row in item["candidateEvidence"]],
                                "contextChunkIds": [row["vectorDocumentId"] for row in item["evidence"]],
                                "vectorChunkIds": [row["vectorDocumentId"] for row in item["vectorEvidence"]],
                                "lexicalChunkIds": [row["vectorDocumentId"] for row in item["lexicalEvidence"]]})
                if "vectorSearchCalls" in item:
                    records[-1].update(vectorSearchCalls=item["vectorSearchCalls"],
                                       contextTokenBudget=item["contextTokenBudget"],
                                       contextTokenEstimate=item["contextTokenEstimate"],
                                       focusedQueries=[row["query"] for row in item["focusedQueries"]])
                if "identifierReservations" in item:
                    records[-1]["identifierReservations"] = item["identifierReservations"]
        except (requests.RequestException, ValueError, KeyError) as exc:
            write_json(output / "batch-failure.json", {"startCase": start, "errorType": type(exc).__name__,
                       "message": str(exc), "allCaseDenominatorPreserved": True, "automaticRetry": False})
            break
        (output / "records.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in records), encoding="utf-8")
        print(json.dumps({"mode": args.mode, "completedCases": len(records), "totalCases": len(cases)}), flush=True)
    (output / "records.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in records), encoding="utf-8")
    report = score(gold, variant, records, args.split)
    report.update(mode=args.mode, corpusChunks=len(variant), generatorCalled=False, judgeCalled=False,
                  rewriteRequested=False, latencyScope="actual ordered development API pass; diagnostic, not controlled latency A/B",
                  evidenceScope="actual controlled official corpus; pack declares ingestion provenance and same-Agent review; no production claim")
    write_json(output / "scores.json", report)
    write_json(output / "effective-retrieval-config.json", configuration)
    print(json.dumps({key: report[key] for key in ("cases", "answerable", "missing", "failures", "candidateAllRequiredRecall",
                      "contextAllRequiredRecall", "requiredConditions", "contextRequirementMicroCoverage")}), flush=True)
    if report["missing"] or report["failures"]:
        raise SystemExit(2)


if __name__ == "__main__":
    main()
