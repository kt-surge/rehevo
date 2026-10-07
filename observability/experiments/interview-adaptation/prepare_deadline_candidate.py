"""Freeze the cancellation implementation and required full-backend regression inputs."""
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
name = sys.argv[1] if len(sys.argv) == 2 else 'evaluation-deadline-candidate-20261005-r3'
assert name.startswith('evaluation-deadline-candidate-20261005-r') and name.rsplit('r', 1)[1].isdigit()
run = ROOT / 'observability/experiments/voice-frame-pipeline/runs' / name
run.mkdir()
(run / 'sources').mkdir()
files = [
    'app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java',
    'app/src/main/java/interview/guide/common/ai/AiCallCancellation.java',
    'app/src/main/java/interview/guide/common/ai/StructuredOutputProperties.java',
    'app/src/main/java/interview/guide/common/ai/ApiPathResolver.java',
    'app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java',
    'app/src/main/java/interview/guide/common/evaluation/UnifiedEvaluationService.java',
    'app/src/main/java/interview/guide/common/evaluation/InterviewEvaluationProperties.java',
    'app/src/main/java/interview/guide/common/config/InterviewEvaluationExecutionConfiguration.java',
    'app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java',
    'app/src/main/java/interview/guide/modules/interview/service/EvaluationBenchmarkService.java',
    'app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewEvaluationService.java',
    'app/src/test/java/interview/guide/common/evaluation/EvaluationExecutionBoundaryTest.java',
    'app/src/test/java/interview/guide/common/evaluation/EvaluationHttpCancellationIntegrationTest.java',
    'app/src/test/java/interview/guide/common/evaluation/UnifiedEvaluationServiceTest.java',
    'app/src/test/java/interview/guide/common/evaluation/EvaluationGroundingRegressionTest.java',
    'app/src/test/java/interview/guide/common/ai/StructuredOutputRetryBudgetTest.java',
    'app/src/test/java/interview/guide/common/ai/AiCallCancellationTest.java',
    'gradle/libs.versions.toml', 'app/build.gradle',
    'observability/experiments/interview-adaptation/EvaluationGroundingStudyMain.java',
    'observability/experiments/interview-adaptation/evaluation-grounding.init.gradle',
    'observability/experiments/interview-adaptation/EVALUATION_DEADLINE_DESIGN_2026-10-05.md',
    'observability/experiments/interview-adaptation/prepare_deadline_candidate.py',
]
manifest = []
for i, name in enumerate(files):
    path = ROOT / name
    raw = Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()
    frozen = f'sources/{i:03d}-{path.name}'
    (run / frozen).write_bytes(raw)
    manifest.append(dict(path=name, frozen=frozen, sha256=hashlib.sha256(raw).hexdigest()))
(run / 'source-manifest.json').write_text(json.dumps(dict(files=manifest), indent=2), encoding='utf-8')
print(json.dumps(dict(prepared=True, fullBackendRegression=True, actualExternalModelCalls=0)))
