"""Save the existing product retrieval API result before diagnostic instrumentation."""
import json
from pathlib import Path
import time

import requests

from fact_gold import load_gold

ROOT = Path(__file__).resolve().parents[3]
RUN = ROOT / "observability/experiments/rag-evaluation/runs/primary-dev-a-20261001"


def main():
    output = RUN / "pre-diagnostic-retrieval.json"
    if output.exists():
        raise ValueError("Baseline exists; do not overwrite")
    gold = load_gold(ROOT / "data/local/fact-gold-v1-20261001-r1/manifest.json", "agent_verified")
    mapping = {row["documentId"]: row["knowledgeBaseId"] for row in
               json.loads((RUN / "ingestion.manifest.json").read_text(encoding="utf-8"))["documents"]}
    # sourceDocumentIds describe Gold provenance, not the search filter. Search all selected docs.
    selected = list(mapping.values())
    payload = {"queries": [{"knowledgeBaseIds": selected, "question": row["question"]}
                           for row in gold.cases.values()], "rewrite": False, "retrievalMode": "HYBRID"}
    (RUN / "pre-diagnostic-request.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    started = time.perf_counter()
    response = requests.post("http://127.0.0.1:18080/api/knowledgebase/evaluation/retrieval", json=payload, timeout=120)
    value = response.json()
    value["capture"] = {"httpStatus": response.status_code, "batchElapsedMs": (time.perf_counter() - started) * 1000,
                        "perCaseTimingAvailable": False, "scope": "real API, isolated four-document dev corpus, no generation"}
    output.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    if response.status_code != 200 or value.get("code") != 200:
        raise ValueError("Retrieval batch failed; raw response preserved")
    print(json.dumps({"cases": len(value["data"]["items"]), "batchElapsedMs": value["capture"]["batchElapsedMs"]}))


if __name__ == "__main__":
    main()
