"""Summarize real runs and offline context-budget projections, preserving their boundary."""
import copy
import json
from pathlib import Path
import shutil
import statistics

from fact_gold import digest, load_gold, load_variant, read_jsonl, score
from ingest_primary_dev import write_json

ROOT = Path(__file__).resolve().parents[3]
RUN = ROOT / "observability/experiments/rag-evaluation/runs/primary-dev-a-20261001"


def main():
    target = RUN / "comparison.json"
    if target.exists():
        raise ValueError("Summary exists; do not overwrite")
    gold = load_gold(ROOT / "data/local/fact-gold-v1-20261001-r2/manifest.json", "agent_verified")
    chunks = load_variant(RUN / "baseline-chunk-variant-r2.json", gold)
    tokens = {key: int(value) for key, value in
              (line.split("\t") for line in (RUN / "chunk-token-estimates.tsv").read_text(encoding="utf-8").splitlines())}
    real = []
    fields = ("mode", "cases", "answerable", "requiredConditions", "failures", "missing",
              "candidateAllRequiredRecall", "contextAllRequiredRecall", "contextRequirementMicroCoverage",
              "elapsedMedianMs", "elapsedP95Ms")
    for label in ("baseline-diagnostic", "adjacent-context-diagnostic", "rerank-diagnostic"):
        result = json.loads((RUN / label / "scores.json").read_text(encoding="utf-8"))
        records = read_jsonl(RUN / label / "records.jsonl")
        sizes = [sum(tokens[item] for item in row["contextChunkIds"]) for row in records]
        real.append({**{key: result[key] for key in fields}, "contextChunkTokensMedianEstimate": statistics.median(sizes),
                     "missingRequiredEvidenceCases": [row["caseId"] for row in result["rows"]
                                                      if row["answerable"] and not row["contextAllRequired"]]})
    records = read_jsonl(RUN / "baseline-diagnostic/records.jsonl")
    projected = []
    for kind, sizes in (("topk", (4, 6, 8, 10, 12, 16, 20)), ("prefix_token_budget", (2000, 3200, 4800, 6400))):
        for size in sizes:
            modified = copy.deepcopy(records)
            counts = []
            for row in modified:
                selected = []
                used = 0
                for chunk_id in row["candidateChunkIds"]:
                    if kind == "topk" and len(selected) >= size:
                        break
                    if kind == "prefix_token_budget" and used + tokens[chunk_id] > size:
                        break
                    selected.append(chunk_id)
                    used += tokens[chunk_id]
                row["contextChunkIds"] = selected
                counts.append(used)
            result = score(gold, chunks, modified, "dev")
            projected.append({"kind": kind, "limit": size, "contextAllRequiredRecall": result["contextAllRequiredRecall"],
                              "contextRequirementMicroCoverage": result["contextRequirementMicroCoverage"],
                              "contextChunkTokensMedianEstimate": statistics.median(counts),
                              "missingRequiredEvidenceCases": [row["caseId"] for row in result["rows"]
                                                               if row["answerable"] and not row["contextAllRequired"]]})
    # Preserve the initial partial snapshot; short names avoid Windows MAX_PATH.
    snapshot = RUN / "snapshot-r1"
    snapshot.mkdir(exist_ok=False)
    # Public Gold inputs are captured for local reproducibility; no .env/provider persistence files.
    shutil.copytree(gold.manifest_path.parent, snapshot / "gold")
    test_destination = snapshot / "tests"
    test_destination.mkdir()
    file_index = {}
    for index, path in enumerate(sorted((ROOT / "app/build/test-results/test").glob("TEST-*.xml"))):
        destination = test_destination / f"t{index:03d}.xml"
        shutil.copyfile(path, destination)
        file_index[path.relative_to(ROOT).as_posix()] = destination.relative_to(snapshot).as_posix()
    paths = list((ROOT / "app/src/main/java/interview/guide/modules/knowledgebase").rglob("*.java"))
    paths += list((ROOT / "app/src/main/resources/prompts").glob("knowledgebase*.st"))
    paths += [ROOT / "app/src/main/resources/application.yml", ROOT / "observability/experiments/runtime/boot-run.ps1"]
    paths += list(Path(__file__).parent.glob("*.py"))
    paths += [Path(__file__).parent / "RehevoChunkTokenCounts.java", Path(__file__).parent / "token-count.init.gradle"]
    for index, path in enumerate(sorted(set(paths))):
        destination = snapshot / "sources" / f"s{index:03d}{path.suffix}"
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, destination)
        file_index[path.relative_to(ROOT).as_posix()] = destination.relative_to(snapshot).as_posix()
    write_json(snapshot / "file-index.json", file_index)
    write_json(snapshot / "files.sha256.json", {path.relative_to(snapshot).as_posix(): digest(path)
               for path in sorted(snapshot.rglob("*")) if path.is_file()})
    summary = {"scope": "same-Agent primary dev diagnostic; 4 official documents, 24 chunks; not final test or production",
               "goldManifestSha256": digest(gold.manifest_path), "realRuns": real,
               "latencyCaveat": "one ordered pass per mode, 34 samples; diagnostic only, not randomized paired performance A/B",
               "tokenCaveat": "Spring AI JTokkitTokenCountEstimator chunk text only; excludes prompts and labels; not provider billed tokens",
               "offlineProjections": projected, "projectionCaveat": "reuse saved candidates; no new retrieval/generation, no projected latency claim",
               "decision": "keep default HYBRID; neither tested alternative improved all-required context coverage",
               "generationPreflight": "current same chat model free quota exhausted; no answers or judge scores",
               "voiceProvider": "same TTS model free quota exhausted; no real voice performance A/B",
               "newTestSplitExists": False, "humanReviewed": False, "secretValuesRecorded": False}
    write_json(target, summary)
    print(json.dumps({"realRuns": real, "offlineProjections": projected}, ensure_ascii=False))


if __name__ == "__main__":
    main()
