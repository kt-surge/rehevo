"""One real model preflight, keeping raw answers/evidence or failure without quality claims."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import time

import requests

from fact_gold import load_gold
from ingest_primary_dev import write_json

ROOT = Path(__file__).resolve().parents[3]
RUN = ROOT / "observability/experiments/rag-evaluation/runs/primary-dev-a-20261001"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=("rerank", "generation"))
    args = parser.parse_args()
    output = RUN / (args.stage + "-preflight")
    output.mkdir(exist_ok=False)
    gold = load_gold(ROOT / "data/local/fact-gold-v1-20261001-r2/manifest.json", "agent_verified")
    selected = [row["knowledgeBaseId"] for row in json.loads((RUN / "ingestion.manifest.json").read_text(encoding="utf-8"))["documents"]]
    case = gold.cases["primary-dev-cross-02"]
    mode = "HYBRID_RERANK" if args.stage == "rerank" else "HYBRID"
    endpoint = "retrieval" if args.stage == "rerank" else "answers"
    payload = {"queries": [{"knowledgeBaseIds": selected, "question": case["question"]}],
               "rewrite": False, "retrievalMode": mode}
    write_json(output / "request.json", payload)
    started = time.perf_counter()
    print(json.dumps({"stage": args.stage, "caseId": case["id"], "state": "requesting"}), flush=True)
    response = requests.post("http://127.0.0.1:18080/api/knowledgebase/evaluation/" + endpoint, json=payload, timeout=120)
    value = response.json()
    write_json(output / "raw-response.json", {"stage": args.stage, "caseId": case["id"],
               "httpStatus": response.status_code, "capturedAt": datetime.now(timezone.utc).isoformat(),
               "elapsedMs": (time.perf_counter() - started) * 1000, "result": value,
               "qualityEvaluationPerformed": False})
    successful = response.status_code == 200 and value.get("code") == 200
    print(json.dumps({"stage": args.stage, "successful": successful,
                      "code": value.get("code"), "errorMessage": None if successful else value.get("message"),
                      "qualityEvaluationPerformed": False}), flush=True)


if __name__ == "__main__":
    main()
