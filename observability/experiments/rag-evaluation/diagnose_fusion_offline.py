"""Development-only fusion sweep over saved actual candidate ranks, with per-query budget caps.

No provider/server request. Does not tune defaults or produce held-out/latency/answer results.
"""
import json
from pathlib import Path
import shutil
import statistics

from fact_gold import digest, load_gold, load_variant, read_jsonl, score
from ingest_primary_dev import write_json

ROOT = Path(__file__).resolve().parents[3]
BASELINE = Path(__file__).parent / "runs/primary-dev-a-20261001"
RUN = Path(__file__).parent / "runs/fusion-offline-dev-20261002-r1"


def fused(row, k, vector_weight, lexical_weight):
    weights = {}
    for key, weight in (("vectorChunkIds", vector_weight), ("lexicalChunkIds", lexical_weight)):
        for rank, chunk in enumerate(row[key], 1):
            weights[chunk] = weights.get(chunk, 0.0) + weight / (k + rank)
    return sorted(weights, key=lambda chunk: (-weights[chunk], chunk))[:20]


def main():
    RUN.mkdir(exist_ok=False)
    gold = load_gold(ROOT / "data/local/fact-gold-v1-20261001-r2/manifest.json", "agent_verified")
    chunks = load_variant(BASELINE / "baseline-chunk-variant-r2.json", gold)
    records = read_jsonl(BASELINE / "baseline-diagnostic/records.jsonl")
    tokens = {key: int(value) for key, value in
              (line.split("\t") for line in (BASELINE / "chunk-token-estimates.tsv").read_text(encoding="utf-8").splitlines())}
    if any(fused(row, 60, 3.0, 1.0) != row["candidateChunkIds"] for row in records):
        raise ValueError("Saved current Java fusion order not exactly reproduced; stop projections")
    baseline = score(gold, chunks, records, "dev")
    write_json(RUN / "baseline-scores.json", baseline)
    budget = {row["caseId"]: sum(tokens[c] for c in row["contextChunkIds"]) for row in records}
    write_json(RUN / "per-query-fixed-budgets.json", budget)
    summaries = []
    for k in (10, 30, 60, 120):
        for vector_weight, lexical_weight in ((1, 0), (0, 1), (1, 1), (2, 1), (3, 1), (5, 1)):
            label = f"k{k}-v{vector_weight}-l{lexical_weight}"
            selected = []
            sizes = []
            for original in records:
                row = {"caseId": original["caseId"], "status": original["status"],
                       "elapsedMs": original["elapsedMs"],
                       "elapsedProvenance": "saved original retrieval measurement; not projected strategy latency",
                       "candidateChunkIds": fused(original, k, vector_weight, lexical_weight)}
                context = []
                used = 0
                for chunk in row["candidateChunkIds"]:
                    if len(context) == len(original["contextChunkIds"]):
                        break
                    if used + tokens[chunk] <= budget[row["caseId"]]:
                        context.append(chunk)
                        used += tokens[chunk]
                row["contextChunkIds"] = context
                selected.append(row)
                sizes.append(used)
            scores = score(gold, chunks, selected, "dev")
            scores.pop("elapsedMedianMs", None)
            scores.pop("elapsedP95Ms", None)
            scores["latencyComparisonAllowed"] = False
            write_json(RUN / f"{label}-scores.json", scores)
            (RUN / f"{label}-records.jsonl").write_text(
                "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in selected), encoding="utf-8")
            summaries.append({"label": label, "rrfK": k, "vectorWeight": vector_weight,
                "lexicalWeight": lexical_weight, "candidateAllRequiredRecall": scores["candidateAllRequiredRecall"],
                "contextAllRequiredRecall": scores["contextAllRequiredRecall"],
                "contextRequirementMicroCoverage": scores["contextRequirementMicroCoverage"],
                "medianChunkTokensEstimate": statistics.median(sizes),
                "noPerQueryContextBudgetIncrease": True,
                "missingCases": [row["caseId"] for row in scores["rows"]
                                 if row["answerable"] and not row["contextAllRequired"]]})
    shutil.copytree(gold.manifest_path.parent, RUN / "gold-input")
    inputs = [BASELINE / "baseline-chunk-variant-r2.json", BASELINE / "chunk-token-estimates.tsv",
              BASELINE / "baseline-diagnostic/records.jsonl", BASELINE / "baseline-diagnostic/effective-retrieval-config.json"]
    input_dir = RUN / "frozen-inputs"
    input_dir.mkdir()
    input_index = {}
    for n, path in enumerate(inputs):
        frozen = f"i{n}{path.suffix}"
        shutil.copyfile(path, input_dir / frozen)
        input_index[path.relative_to(ROOT).as_posix()] = {"file": frozen, "sha256": digest(path)}
    write_json(input_dir / "file-index.json", input_index)
    sources = [Path(__file__), Path(__file__).parent / "fact_gold.py",
               ROOT / "app/src/main/java/interview/guide/modules/knowledgebase/service/HybridRetrievalService.java",
               ROOT / "app/src/main/java/interview/guide/modules/knowledgebase/repository/VectorRepository.java"]
    source_dir = RUN / "sources"
    source_dir.mkdir()
    index = {}
    for n, path in enumerate(sources):
        frozen = f"s{n}{path.suffix}"
        shutil.copyfile(path, source_dir / frozen)
        index[path.relative_to(ROOT).as_posix()] = {"file": frozen, "sha256": digest(path)}
    write_json(source_dir / "file-index.json", index)
    write_json(RUN / "comparison.json", {"scope": "saved-candidate development projection; same-Agent Gold; four official documents/24 chunks",
        "currentFusionRankingExactlyReproduced": True, "baselineContextAllRequiredRecall": baseline["contextAllRequiredRecall"],
        "baselineContextRequirementMicroCoverage": baseline["contextRequirementMicroCoverage"],
        "baselineMedianChunkTokensEstimate": statistics.median(budget.values()), "variants": summaries,
        "selection": "whole chunks, skip those exceeding fixed per-query baseline chunk-token cap; at most original context count",
        "providerOrServerCalled": False, "newTestEvaluated": False, "answerOrLatencyResult": False,
        "defaultChanged": False, "decision": "use projections to choose a bounded real API experiment; broaden dev and freeze strategy before held-out test",
        "limits": ["No new candidate retrieval, so missing original candidates cannot be recovered.",
                   "Twenty candidates from a 24-chunk corpus create a coverage ceiling effect.",
                   "Budget is local chunk text only, not full prompt or billed supplier tokens.",
                   "Contract-required elapsedMs is copied from saved original retrieval, explicitly marked; projection aggregate latency omitted.",
                   "Sweep is selected using exposed dev labels, not independent generalization evidence."]})
    manifest = {p.relative_to(RUN).as_posix(): digest(p) for p in sorted(RUN.rglob("*")) if p.is_file()}
    write_json(RUN / "artifacts.sha256.json", manifest)
    mismatches = [name for name, expected in manifest.items() if digest(RUN / name) != expected]
    if mismatches:
        raise ValueError("Closed offline artifacts changed")
    write_json(RUN / "post-freeze-verification.json", {"frozenArtifacts": len(manifest), "hashMismatches": mismatches,
        "providerOrServerCalled": False, "defaultChanged": False})
    ranked = sorted(summaries, key=lambda row: (-row["contextAllRequiredRecall"], row["medianChunkTokensEstimate"]))
    print(json.dumps({"baselineContextAllRequiredRecall": baseline["contextAllRequiredRecall"],
        "topProjectedVariants": ranked[:5], "variants": len(summaries), "frozenArtifacts": len(manifest),
        "scope": "offline exposed-dev projection only; no runtime gain"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
