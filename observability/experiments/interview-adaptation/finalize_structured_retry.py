"""Record terminal offline versions, not model performance, and freeze executed source."""
import hashlib
import json
import os
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNS = ROOT / "observability/experiments/voice-frame-pipeline/runs"


def read(path):
    return Path("\\\\?\\" + str(path.resolve())).read_bytes() if os.name == "nt" else path.read_bytes()


for version in ("baseline-20261005-r1", "baseline-20261005-r2", "baseline-20261005-r3", "candidate-20261005-r1"):
    run = RUNS / ("structured-retry-" + version)
    files = list(run.rglob("TEST-*.xml"))
    summary = dict(suites=len(files), tests=0, failures=0, errors=0, skipped=0, externalModelCalls=0)
    for file in files:
        suite = ET.fromstring(read(file))
        for key in ("tests", "failures", "errors", "skipped"):
            summary[key] += int(suite.attrib.get(key, 0))
    summary["validBehaviorBaseline"] = version == "baseline-20261005-r3"
    if version == "baseline-20261005-r1":
        summary["note"] = "compile failure; tests NOT_RUN"
    elif version == "baseline-20261005-r2":
        summary["note"] = "mock getOptions missing; fake model call count zero; not retry behavior evidence"
    elif version == "baseline-20261005-r3":
        summary["note"] = "central budget=2, continuously invalid JSON produced 8 actual fake ChatModel calls"
    (run / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    report = ROOT / "observability/experiments/interview-adaptation/STRUCTURED_RETRY_RESULTS_2026-10-05.md"
    (run / report.name).write_bytes(read(report))
    (run / "finalize_structured_retry.py").write_bytes(read(Path(__file__)))
    print(json.dumps(dict(run=run.name, summary=summary)))

candidate = RUNS / "structured-retry-candidate-20261005-r1"
sources = candidate / "sources"
sources.mkdir()
paths = [ROOT / name for name in (
    "app/build.gradle", "gradle/libs.versions.toml",
    "app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java",
    "app/src/main/java/interview/guide/common/ai/StructuredOutputProperties.java",
    "app/src/main/java/interview/guide/common/evaluation/UnifiedEvaluationService.java",
    "app/src/test/java/interview/guide/common/ai/StructuredOutputRetryBudgetTest.java",
    "app/src/test/java/interview/guide/common/evaluation/UnifiedEvaluationServiceTest.java",
    "app/src/test/java/interview/guide/common/evaluation/EvaluationGroundingRegressionTest.java",
)]
manifest = []
for index, path in enumerate(paths):
    raw = read(path)
    frozen = "sources/" + f"{index:03d}-{path.name}"
    (candidate / frozen).write_bytes(raw)
    manifest.append(dict(path=path.relative_to(ROOT).as_posix(), frozen=frozen,
                         sha256=hashlib.sha256(raw).hexdigest()))
(candidate / "source-manifest.json").write_text(json.dumps(dict(files=manifest), indent=2), encoding="utf-8")
