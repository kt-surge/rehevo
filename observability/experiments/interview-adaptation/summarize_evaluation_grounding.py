"""Preserve regression and model diagnostic negatives without turning them into effect claims."""
import hashlib
import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNS = ROOT / "observability/experiments/voice-frame-pipeline/runs"
sys.path.insert(0, str(Path(__file__).parent))
from probe_training_history_product import data_scope


def read(path):
    return Path("\\\\?\\" + str(path.resolve())).read_bytes() if os.name == "nt" else path.read_bytes()


def save(run, name, value):
    path = run / name
    if path.exists():
        if json.loads(read(path)) == json.loads(json.dumps(value)):
            return
        raise ValueError(f"Preserve differing existing {name}")
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def tests(run):
    files = list(run.rglob("TEST-*.xml"))
    result = dict(suites=len(files), tests=0, failures=0, errors=0, skipped=0)
    for file in files:
        element = ET.fromstring(read(file))
        for key in ("tests", "failures", "errors", "skipped"):
            result[key] += int(element.attrib.get(key, 0))
    return result


def counters(run):
    meters = json.loads(read(run / "meters-after.json"))
    result = dict(input=0, output=0, total=0, observedChatOperations=0)
    for meter in meters:
        tags = {item["key"]: item["value"] for item in meter["tags"]}
        values = {item["statistic"]: item["value"] for item in meter["measurements"]}
        if tags.get("gen_ai.operation.name") != "chat":
            continue
        if meter["name"] == "gen_ai.client.token.usage":
            result[tags["gen_ai.token.type"]] += values["COUNT"]
        elif meter["name"] == "gen_ai.client.operation":
            result["observedChatOperations"] += values["COUNT"]
    assert result["total"] == result["input"] + result["output"]
    return result


scope = data_scope()
results = {}
for version in ("baseline-20261005-r1", "candidate-20261005-r1", "candidate-20261005-r2"):
    run = RUNS / ("evaluation-grounding-" + version)
    result = tests(run)
    results[version] = result
    if not (run / "summary.json").exists():
        save(run, "summary.json", result)
for arm in ("baseline", "candidate"):
    run = RUNS / f"evaluation-grounding-live-{arm}-20261005-r1"
    report = json.loads(read(run / "report.json"))
    inputs = json.loads(read(run / "inputs.json"))
    parsed = json.loads(read(run / "01-parsed-result.json"))["questionEvaluations"]
    eligible = {q["questionIndex"] for q in report["questionDetails"]
                if q["evaluationStatus"] == "SCORED" and q.get("userAnswer") and q.get("answerEvidence")}
    invalid_tasks = [index for index, task in enumerate(report["trainingTasks"])
                     if not task["questionIndexes"] or not set(task["questionIndexes"]).issubset(eligible)]
    by_index = {q["questionIndex"]: q for q in inputs["qaRecords"]}
    model_evidence_matches = {item["questionIndex"]: [text for text in item.get("answerEvidence", [])
        if text and text in (by_index.get(item["questionIndex"], {}).get("userAnswer") or "")] for item in parsed}
    audit = dict(expectedRawIndexes=list(by_index), modelRawIndexes=[item["questionIndex"] for item in parsed],
                 modelEvidenceMatches=model_evidence_matches, invalidTaskOrdinals=invalid_tasks,
                 reportAnswered=report["answeredQuestions"], reportScored=report["scoredQuestions"],
                 reportEvidenceSupported=report["evidenceSupportedQuestions"], qualityGate="HOLD",
                 noAccuracyOrLatencyEffectClaim=True,
                 executor="inline diagnostic; not production bounded executor",
                 activeChatOperationsAtTerminal=0)
    save(run, "semantic-audit.json", audit)
    usage = counters(run)
    usage.update(supplierHttpRequests="not-recorded; observed SDK operations are not wire request counts",
                 embedding=0, asr=0, tts=0, crossModelComparisons=0)
    save(run, "usage-summary.json", usage)
    save(run, "scope-after.json", scope)
    results[arm] = dict(audit=audit, usage=usage)
left = RUNS / "evaluation-grounding-live-baseline-20261005-r1"
right = RUNS / "evaluation-grounding-live-candidate-20261005-r1"
assert read(left / "inputs.json") == read(right / "inputs.json")
assert json.loads(read(left / "reference-context.json")) == json.loads(read(right / "reference-context.json"))
comparison = dict(inputsByteIdentical=True, referenceObjectIdentical=True,
                  referenceBodySha256=hashlib.sha256(json.loads(read(left / "reference-context.json"))[
                      "referenceContext"].encode()).hexdigest(),
                  collectionRepair="initial byte comparison rejected JSON key ordering; objects and reference body are identical")
for run in (left, right):
    save(run, "comparison-integrity.json", comparison)
print(json.dumps(results, ensure_ascii=True))
