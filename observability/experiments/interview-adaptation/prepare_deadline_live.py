"""One already-exposed public normal-evaluation preflight after cancellation regression."""
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).parent))
from probe_training_history_product import data_scope, provider_snapshot


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


runs = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
tested = runs / 'evaluation-deadline-candidate-20261005-r6'
assert 'BUILD SUCCESSFUL' in read(tested / 'full-backend.log').decode('utf-8', errors='replace')
manifest = json.loads(read(tested / 'source-manifest.json'))
for item in manifest['files']:
    assert hashlib.sha256(read(ROOT / item['path'])).hexdigest() == item['sha256'], item['path']
providers = provider_snapshot()
assert [p['model'] for p in providers['providers'] if p.get('defaultChatProvider')] == ['qwen3.8-flash']
run = runs / 'evaluation-deadline-live-candidate-20261005-r1'
run.mkdir()
(run / 'sources').mkdir()
for item in manifest['files']:
    (run / item['frozen']).write_bytes(read(tested / item['frozen']))
extra = ['app/src/main/resources/application.yml',
    'app/src/main/java/interview/guide/common/ai/PromptSecurityConstants.java',
    'observability/experiments/interview-adaptation/prepare_deadline_live.py']
extra += ['app/src/main/resources/prompts/' + name for name in (
    'interview-evaluation-system.st', 'interview-evaluation-user.st',
    'interview-evaluation-summary-system.st', 'interview-evaluation-summary-user.st')]
for name in extra:
    raw = read(ROOT / name)
    frozen = f"sources/{len(manifest['files']):03d}-{Path(name).name}"
    (run / frozen).write_bytes(raw)
    manifest['files'].append(dict(path=name, frozen=frozen, sha256=hashlib.sha256(raw).hexdigest()))
(run / 'inputs.json').write_bytes(read(runs / 'evaluation-closed-preflight-20261005-r1/inputs.json'))
for name, value in [('source-manifest.json', manifest), ('scope-before.json', data_scope()),
    ('provider-before.json', providers), ('preflight-plan.json', dict(reports=1, toolAccess='NONE',
        defaultModel='qwen3.8-flash', alreadyExposedInputs=True, maxInvokerOperations=3,
        crossModelComparisons=0, noSessionWrites=True, noFormalQualityOrSpeedClaim=True))]:
    (run / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(prepared=True, reports=1, toolAccess='NONE', crossModelComparisons=0)))
