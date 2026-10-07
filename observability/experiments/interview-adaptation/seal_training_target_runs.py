"""Finish this phase's evidence packets before irreversible hash sealing; preserve prior runs."""
import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNS = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
NAMES = ('training-target-baseline-20261005-r1', 'training-target-candidate-20261005-r1',
         'training-target-candidate-20261005-r2', 'training-target-live-candidate-20261005-r1',
         'training-target-live-baseline-20261005-r1')


def long(path):
    return Path('\\\\?\\' + str(path.resolve())) if os.name == 'nt' else path


def read(path):
    return long(path).read_bytes()


def create(path, raw):
    if long(path).exists():
        assert read(path) == raw, path.name
    else:
        long(path).write_bytes(raw)


def save(path, value):
    create(path, json.dumps(value, ensure_ascii=False, indent=2).encode('utf-8'))


baseline = RUNS / NAMES[0]
assert not (baseline / 'artifacts.sha256.json').exists()
root = ET.fromstring(read(baseline / 'TEST-interview.guide.modules.interview.service.TrainingTargetAssociationRegressionTest.xml'))
assert int(root.get('tests')) == 5 and int(root.get('failures')) == 4
save(baseline / 'test-summary.json', dict(tests=5, failures=4, errors=0, skipped=0,
    actualExecutedTests=True, externalModelCalls=0,
    assembly='old behavior plus unused new-field bridge; distinct from the live original-class counterpart'))
source_paths = {
    'InterviewPlan.java': 'app/src/main/java/interview/guide/modules/interview/model/InterviewPlan.java',
    'QuestionEvaluationGuide.java': 'app/src/main/java/interview/guide/common/evaluation/QuestionEvaluationGuide.java',
    'InterviewPlanService.java': 'app/src/main/java/interview/guide/modules/interview/service/InterviewPlanService.java',
    'InterviewQuestionService.java': 'app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java',
    'TrainingTargetAssociationRegressionTest.java': 'test-assembly/TrainingTargetAssociationRegressionTest.java',
    'TrainingTargetSelector.java': 'test-assembly/TrainingTargetSelector.java',
    'bridge-QuestionEvaluationGuide.java': 'test-assembly/bridge-QuestionEvaluationGuide.java',
    'bridge-InterviewPlan.java': 'test-assembly/bridge-InterviewPlan.java',
    'TRAINING_TARGET_DESIGN_2026-10-05.md': 'design/initial-TRAINING_TARGET_DESIGN_2026-10-05.md',
}
source_paths.update({name: 'app/src/main/resources/prompts/' + name for name in (
    'interview-question-skill-system.st', 'interview-question-skill-user.st',
    'interview-question-resume-system.st', 'interview-question-resume-user.st')})
save(baseline / 'source-manifest.json', dict(files=[dict(path=path, frozen=name,
    sha256=hashlib.sha256(read(baseline / name)).hexdigest()) for name, path in source_paths.items()]))
# The UI wording/types did not change after the r1 build; do not fabricate a second build.
r1, r2 = RUNS / NAMES[1], RUNS / NAMES[2]
m1 = json.loads(read(r1 / 'source-manifest.json'))['files']
m2 = json.loads(read(r2 / 'source-manifest.json'))['files']
assert {i['path']: i['sha256'] for i in m1 if i['path'].startswith('frontend/')} == {
    i['path']: i['sha256'] for i in m2 if i['path'].startswith('frontend/')}
save(r2 / 'frontend-build-proof.json', dict(buildOrigin=NAMES[1],
    sourceHashesIdentical=True, noSecondBuildClaim=True))
for name in NAMES:
    run = RUNS / name
    assert not (run / 'artifacts.sha256.json').exists()
    create(run / 'review-results.md', read(ROOT / 'observability/experiments/interview-adaptation/TRAINING_TARGET_RESULTS_2026-10-05.md'))
    audit = run / 'audit-sources'
    audit.mkdir(exist_ok=True)
    for file in ('collect_training_target_tests.py', 'collect_training_target_live.py',
                 'seal_training_target_runs.py', 'prepare_training_target_baseline.py'):
        create(audit / file, read(Path(__file__).with_name(file)))
    save(run / 'audit-source-role.json', dict(role='post-execution collection/review helpers; not production source or model runner',
        files={file.name: hashlib.sha256(read(file)).hexdigest() for file in audit.iterdir()}))
for name in NAMES:
    subprocess.run([sys.executable, str(ROOT / 'observability/experiments/tts-streaming/seal_voice_run.py'),
                    str(RUNS / name)], check=True)
