"""Prepare one already-exposed public NONE preflight; no production/model changes."""
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).parent))
from probe_training_history_product import data_scope, provider_snapshot


def read(path):
    return Path("\\\\?\\" + str(path.resolve())).read_bytes() if os.name == "nt" else path.read_bytes()


run = ROOT / "observability/experiments/voice-frame-pipeline/runs/evaluation-closed-preflight-20261005-r1"
run.mkdir()
scope = data_scope()
providers = provider_snapshot()
assert [p["model"] for p in providers["providers"] if p.get("defaultChatProvider")] == ["qwen3.8-flash"]
original = ROOT / "observability/experiments/voice-frame-pipeline/runs/evaluation-grounding-live-candidate-20261005-r1/inputs.json"
(run / "inputs.json").write_bytes(read(original))
for name, value in (("scope-before.json", scope), ("provider-before.json", providers),
                    ("preflight-plan.json", dict(toolAccess="NONE", defaultModel="qwen3.8-flash",
                         reports=1, inputsAlreadyExposed=True, crossModelComparisons=0,
                         maxInvokerOperations=3, notFormalQualityOrPerformance=True))):
    (run / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
sources = run / "sources"
sources.mkdir()
paths = [ROOT / name for name in (
    "app/src/main/java/interview/guide/common/evaluation/UnifiedEvaluationService.java",
    "app/src/main/java/interview/guide/common/evaluation/InterviewEvaluationProperties.java",
    "app/src/main/java/interview/guide/common/evaluation/EvaluationReport.java",
    "app/src/main/java/interview/guide/common/evaluation/QaRecord.java",
    "app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java",
    "app/src/main/java/interview/guide/common/ai/StructuredOutputProperties.java",
    "app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java",
    "app/src/main/java/interview/guide/common/ai/PromptSecurityConstants.java",
    "app/src/main/resources/application.yml",
    "observability/experiments/interview-adaptation/EvaluationGroundingStudyMain.java",
    "observability/experiments/interview-adaptation/evaluation-grounding.init.gradle",
    "observability/experiments/interview-adaptation/CLOSED_INPUT_EVALUATION_DESIGN_2026-10-05.md",
    "observability/experiments/interview-adaptation/prepare_closed_evaluation.py",
)]
paths += [ROOT / "app/src/main/resources/prompts" / name for name in (
    "interview-evaluation-system.st", "interview-evaluation-user.st",
    "interview-evaluation-summary-system.st", "interview-evaluation-summary-user.st")]
manifest = []
for index, path in enumerate(paths):
    raw = read(path)
    file = f"sources/{index:03d}-{path.name}"
    (run / file).write_bytes(raw)
    manifest.append(dict(path=path.relative_to(ROOT).as_posix(), frozen=file,
                         sha256=hashlib.sha256(raw).hexdigest()))
(run / "source-manifest.json").write_text(json.dumps(dict(files=manifest), indent=2), encoding="utf-8")
print(json.dumps(dict(prepared=True, reports=1, toolAccess="NONE", publicOnly=True)))
