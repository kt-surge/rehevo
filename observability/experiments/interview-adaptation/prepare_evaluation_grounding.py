"""Freeze diagnostic source and inputs before executing either real model arm."""
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNS = ROOT / "observability/experiments/voice-frame-pipeline/runs"
sys.stdout.reconfigure(encoding="utf-8")


def read(path):
    return Path("\\\\?\\" + str(path.resolve())).read_bytes() if os.name == "nt" else path.read_bytes()


def freeze(run, paths):
    sources = run / "sources"
    sources.mkdir()
    manifest = []
    for index, path in enumerate(paths):
        raw = read(path)
        file = f"{index:03d}-{path.name}"
        (sources / file).write_bytes(raw)
        manifest.append(dict(path=path.relative_to(ROOT).as_posix(), frozen="sources/" + file,
                             sha256=hashlib.sha256(raw).hexdigest()))
    (run / "source-manifest.json").write_text(json.dumps(dict(files=manifest), indent=2), encoding="utf-8")


baseline = RUNS / "evaluation-grounding-baseline-20261005-r1"
paths = [ROOT / path for path in (
    "app/src/main/java/interview/guide/common/evaluation/UnifiedEvaluationService.java",
    "app/src/main/java/interview/guide/common/evaluation/QaRecord.java",
    "app/src/main/java/interview/guide/common/evaluation/QuestionEvaluationGuide.java",
    "app/src/main/java/interview/guide/common/evaluation/EvaluationReport.java",
    "app/src/main/java/interview/guide/common/evaluation/InterviewEvaluationProperties.java",
    "app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java",
    "app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java",
    "app/src/main/java/interview/guide/common/ai/PromptSecurityConstants.java",
    "app/src/main/java/interview/guide/common/ai/StructuredOutputProperties.java",
    "app/src/main/java/interview/guide/common/config/LlmProviderProperties.java",
    "app/src/main/resources/application.yml",
    "observability/experiments/interview-adaptation/EVALUATION_GROUNDING_DESIGN_2026-10-05.md",
    "observability/experiments/interview-adaptation/EvaluationGroundingStudyMain.java",
    "observability/experiments/interview-adaptation/evaluation-grounding.init.gradle",
    "observability/experiments/interview-adaptation/prepare_evaluation_grounding.py",
)]
paths += [ROOT / "app/src/main/resources/prompts" / name for name in (
    "interview-evaluation-system.st", "interview-evaluation-user.st",
    "interview-evaluation-summary-system.st", "interview-evaluation-summary-user.st")]
paths += list((ROOT / "app/src/main/resources/skills/java-backend").rglob("*.*"))
paths += list((ROOT / "app/src/main/resources/skills/_shared/references").rglob("*.md"))
candidate = RUNS / "evaluation-grounding-candidate-20261005-r2"
freeze(candidate, paths + [ROOT / "app/src/test/java/interview/guide/common/evaluation" / name
                           for name in ("EvaluationGroundingRegressionTest.java", "UnifiedEvaluationServiceTest.java")])
payload = json.loads(read(RUNS / "training-history-product-20261005-r1/voice-evaluation.json"))
answers = payload["data"]["evaluation"]["answers"]
qa = [{key: item.get(key) for key in ("questionIndex", "question", "category", "userAnswer")} for item in answers]
assert len(qa) == 2 and qa[0]["questionIndex"] == 0 and qa[1]["userAnswer"] is None
study = dict(payloadOrigin="sealed-public-training-history-product-r1", skillId="java-backend", qaRecords=qa)
for arm in ("baseline", "candidate"):
    run = RUNS / f"evaluation-grounding-live-{arm}-20261005-r1"
    run.mkdir()
    (run / "inputs.json").write_text(json.dumps(study, ensure_ascii=False, indent=2), encoding="utf-8")
    effective = list(paths)
    if arm == "baseline":
        override = run / "override"
        override.mkdir()
        shutil.copyfile(baseline / "baseline-UnifiedEvaluationService.java", override / "UnifiedEvaluationService.java")
        resources = override / "resources/prompts"
        resources.mkdir(parents=True)
        for name in ("interview-evaluation-system.st", "interview-evaluation-user.st",
                     "interview-evaluation-summary-system.st", "interview-evaluation-summary-user.st"):
            shutil.copyfile(baseline / name, resources / name)
        effective += [override / "UnifiedEvaluationService.java"] + list(resources.glob("*.st"))
    freeze(run, effective + [run / "inputs.json"])
print(json.dumps(dict(prepared=True, qaRecords=len(qa), arms=2, currentDefaultModelUnchanged=True)))
