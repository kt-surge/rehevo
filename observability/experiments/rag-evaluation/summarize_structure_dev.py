"""Compare actual A/B retrieval and separately label offline equal-budget projections."""
import copy
import json
from pathlib import Path
import shutil
import statistics

from fact_gold import digest, load_gold, load_variant, read_jsonl, score
from ingest_primary_dev import ROOT, write_json

RUN = ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001"
A_RUN = ROOT / "observability/experiments/rag-evaluation/runs/primary-dev-a-20261001"


def summarize(gold, directory, label, name):
    variant = load_variant(directory / "baseline-chunk-variant-r2.json", gold)
    records = read_jsonl(directory / label / "records.jsonl")
    report = score(gold, variant, records, "dev")
    tokens = {key: int(value) for key, value in (line.split("\t") for line in
              (directory / "chunk-token-estimates.tsv").read_text(encoding="utf-8").splitlines())}
    lengths = [sum(tokens[key] for key in row["contextChunkIds"]) for row in records]
    actual = {"strategy": name, "chunks": len(variant), "cases": report["cases"],
              "answerable": report["answerable"], "requiredConditions": report["requiredConditions"],
              **{key: report[key] for key in ("failures", "missing", "candidateAllRequiredRecall",
                  "contextAllRequiredRecall", "contextRequirementMicroCoverage", "elapsedMedianMs", "elapsedP95Ms")},
              "contextTokenMedianEstimate": statistics.median(lengths),
              "contextTokenMaxEstimate": max(lengths),
              "chunkTokenMinEstimate": min(tokens.values()), "chunkTokenMaxEstimate": max(tokens.values()),
              "missingRequiredEvidenceCases": [row["caseId"] for row in report["rows"]
                                               if row["answerable"] and not row["contextAllRequired"]]}
    projections = []
    for budget in (2000, 3200, 4800, 6400):
        modified = copy.deepcopy(records)
        sizes = []
        for row in modified:
            used, selected = 0, []
            for chunk_id in row["candidateChunkIds"]:
                if used + tokens[chunk_id] > budget:
                    break
                selected.append(chunk_id)
                used += tokens[chunk_id]
            row["contextChunkIds"] = selected
            sizes.append(used)
        result = score(gold, variant, modified, "dev")
        projections.append({"strategy": name, "budget": budget,
                            "contextTokenMedianEstimate": statistics.median(sizes),
                            "contextAllRequiredRecall": result["contextAllRequiredRecall"],
                            "contextRequirementMicroCoverage": result["contextRequirementMicroCoverage"],
                            "missingRequiredEvidenceCases": [row["caseId"] for row in result["rows"]
                                                             if row["answerable"] and not row["contextAllRequired"]]})
    return actual, projections, report["rows"]


def main():
    if (RUN / "comparison.json").exists():
        raise ValueError("Comparison exists; do not overwrite")
    gold = load_gold(ROOT / "data/local/fact-gold-v1-20261001-r2/manifest.json", "agent_verified")
    actual, projections, rows = [], [], []
    for path, label, name in ((A_RUN, "baseline-diagnostic", "TOKEN A"),
                              (RUN, "structured-diagnostic", "STRUCTURED B")):
        result, projected, samples = summarize(gold, path, label, name)
        actual.append(result)
        projections.extend(projected)
        rows.append({row["caseId"]: row for row in samples})
    paired = [{"caseId": case_id, "aContextAllRequired": row["contextAllRequired"],
               "bContextAllRequired": rows[1][case_id]["contextAllRequired"],
               "a": row, "b": rows[1][case_id]} for case_id, row in rows[0].items()]
    write_json(RUN / "paired-evidence.json", paired)
    snapshot = RUN / "snapshot"
    snapshot.mkdir(exist_ok=False)
    sources = list((ROOT / "app/src/main/java/interview/guide/modules/knowledgebase").rglob("*.java"))
    sources += list((ROOT / "app/src/main/java/interview/guide/infrastructure/file").rglob("*.java"))
    sources += [ROOT / "app/src/main/java/interview/guide/common/config/DocumentChunkingProperties.java",
                ROOT / "app/src/main/resources/application.yml", ROOT / "observability/experiments/runtime/boot-run.ps1"]
    sources += list(Path(__file__).parent.glob("*.py"))
    sources += list((ROOT / "app/src/test/java/interview/guide/infrastructure/file").glob("*.java"))
    file_index = {}
    for index, path in enumerate(sorted(set(sources))):
        target = snapshot / f"s{index:03d}{path.suffix}"
        shutil.copyfile(path, target)
        file_index[path.relative_to(ROOT).as_posix()] = target.name
    for index, path in enumerate(sorted((ROOT / "app/build/test-results/test").glob("TEST-*.xml"))):
        target = snapshot / f"t{index:03d}.xml"
        shutil.copyfile(path, target)
        file_index[path.relative_to(ROOT).as_posix()] = target.name
    write_json(snapshot / "file-index.json", file_index)
    write_json(snapshot / "files.sha256.json", {path.name: digest(path) for path in sorted(snapshot.iterdir()) if path.is_file()})
    write_json(RUN / "comparison.json", {"scope": "34 same-Agent dev cases on four official documents; no new independent test",
               "goldManifestSha256": digest(gold.manifest_path), "actualRuns": actual,
               "offlineEqualBudgetProjections": projections,
               "projectionCaveat": "prefix of saved fused candidates, complete chunks only; no new API/generator calls",
               "latencyCaveat": "one ordered scan per strategy at different times, not randomized paired performance A/B",
               "tokenCaveat": "local Spring AI text estimator; excludes prompts/labels, not provider billed tokens",
               "decision": "experimental only; keep default TOKEN while broader/independent tests and generation are incomplete",
               "generationCalled": False, "humanReviewed": False, "independentTestRun": False})
    print(json.dumps({"actualRuns": actual, "offlineEqualBudgetProjections": projections}, ensure_ascii=False))


if __name__ == "__main__":
    main()
