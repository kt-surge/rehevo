"""Freeze training target regression or one public default-model development preflight."""
import hashlib
import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNS = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
name = sys.argv[1]
assert re.fullmatch(r'training-target-[a-z-]+-20261005-r[1-9][0-9]*', name)
run = RUNS / name
run.mkdir()
(run / 'sources').mkdir()


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


paths = [
    'app/src/main/java/interview/guide/common/evaluation/QuestionEvaluationGuide.java',
    'app/src/main/java/interview/guide/modules/interview/model/InterviewPlan.java',
    'app/src/main/java/interview/guide/modules/interview/service/InterviewPlanService.java',
    'app/src/main/java/interview/guide/modules/interview/service/TrainingTargetSelector.java',
    'app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java',
    'app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionProperties.java',
    'app/src/main/java/interview/guide/modules/interview/skill/InterviewSkillService.java',
    'app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java',
    'app/src/main/java/interview/guide/common/ai/StructuredOutputProperties.java',
    'app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java',
    'app/src/main/java/interview/guide/common/ai/PromptSanitizer.java',
    'app/src/main/java/interview/guide/common/ai/PromptSecurityConstants.java',
    'app/src/main/resources/application.yml',
    'app/src/test/java/interview/guide/modules/interview/service/TrainingTargetAssociationRegressionTest.java',
    'app/src/test/java/interview/guide/modules/interview/service/TrainingTargetSelectorTest.java',
    'app/src/test/java/interview/guide/modules/interview/service/InterviewQuestionTrainingTargetTest.java',
    'app/src/test/java/interview/guide/modules/interview/service/InterviewPlanServiceTest.java',
    'frontend/src/types/interview.ts', 'frontend/src/components/InterviewChatPanel.tsx',
    'observability/experiments/interview-adaptation/TRAINING_TARGET_DESIGN_2026-10-05.md',
    'observability/experiments/interview-adaptation/prepare_training_targets.py',
]
paths += ['app/src/main/resources/prompts/' + file for file in (
    'interview-question-skill-system.st', 'interview-question-skill-user.st',
    'interview-question-resume-system.st', 'interview-question-resume-user.st')]
if 'live' in name:
    sys.path.insert(0, str(Path(__file__).parent))
    from probe_training_history_product import data_scope, provider_snapshot
    provider = provider_snapshot()
    assert [p['model'] for p in provider['providers'] if p.get('defaultChatProvider')] == ['qwen3.8-flash']
    report = json.loads(read(RUNS / 'evaluation-deadline-live-candidate-20261005-r1/report.json'))
    tasks = report['trainingTasks']
    assert tasks and all(task['questionIndexes'] == [0] for task in tasks)
    inputs = dict(payloadOrigin='sealed-public-evaluation-deadline-live-r1', skillId='java-backend',
                  difficulty='mid', questionCount=3, priorTrainingTasks=tasks)
    for file, value in [('inputs.json', inputs), ('scope-before.json', data_scope()),
                        ('provider-before.json', provider), ('preflight-plan.json', dict(
                            defaultModel='qwen3.8-flash', generationRequests=1,
                            maxInvokerOperations=1, centralMaxAttempts=2, alreadyExposedInputs=True,
                            crossModelComparisons=0, noSessionWrites=True, noFormalQualityOrSpeedClaim=True))]:
        (run / file).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
    paths += ['observability/experiments/interview-adaptation/TrainingTargetStudyMain.java',
              'observability/experiments/interview-adaptation/training-target.init.gradle']
manifest = []
for i, file in enumerate(paths):
    path = ROOT / file
    raw = read(path)
    frozen = f'sources/{i:03d}-{path.name}'
    (run / frozen).write_bytes(raw)
    manifest.append(dict(path=file, frozen=frozen, sha256=hashlib.sha256(raw).hexdigest()))
(run / 'source-manifest.json').write_text(json.dumps(dict(files=manifest), indent=2), encoding='utf-8')
print(json.dumps(dict(prepared=True, files=len(manifest), live='live' in name)))
