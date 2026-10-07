"""Prepare a single old-implementation counterpart; preserve original files and current checkout."""
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNS = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
name = 'training-target-live-baseline-20261005-r1'
subprocess.run([sys.executable, str(Path(__file__).with_name('prepare_training_targets.py')), name], check=True)
run = RUNS / name
candidate = RUNS / 'training-target-live-candidate-20261005-r1'
old = RUNS / 'training-target-baseline-20261005-r1'


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


assert read(candidate / 'inputs.json') == read(run / 'inputs.json')
override = run / 'override'
override.mkdir()
templates = override / 'resources/prompts'
templates.mkdir(parents=True)
manifest = []
for name in ('QuestionEvaluationGuide.java', 'InterviewPlan.java', 'InterviewPlanService.java',
             'InterviewQuestionService.java', 'interview-question-skill-system.st',
             'interview-question-skill-user.st', 'interview-question-resume-system.st',
             'interview-question-resume-user.st'):
    path = old / name
    target = templates / name if name.endswith('.st') else override / name
    raw = read(path)
    target.write_bytes(raw)
    manifest.append(dict(path=path.relative_to(ROOT).as_posix(), frozen=target.relative_to(run).as_posix(),
                         sha256=hashlib.sha256(raw).hexdigest()))
# JavaCompile reads the immutable original snapshot, not a production-file rollback.
(run / 'override-source-manifest.json').write_text(json.dumps(dict(files=manifest), indent=2), encoding='utf-8')
(run / 'comparison-plan.json').write_text(json.dumps(dict(
    pairs=1, fixedOrder=['candidate', 'baseline'], alreadyExposed=True,
    inputByteIdentical=True, defaultModel='qwen3.8-flash', crossModelComparisons=0,
    baselineOverrideClasses=[item['path'] for item in manifest if item['path'].endswith('.java')],
    loadedClassLocationMustBeVerified=True, qualityGate='HOLD',
    scope='single diagnostic counterpart only; no formal effect or latency estimate'), indent=2), encoding='utf-8')
print(json.dumps(dict(prepared=True, overrideSources=len(manifest), actualModelCalls=0)))
