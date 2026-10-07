"""Freeze one same-code DEFAULT diagnostic after the NONE preflight, not a formal A/B."""
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
first = runs / 'evaluation-closed-preflight-20261005-r1'
run = runs / 'evaluation-closed-default-20261005-r1'
manifest = json.loads(read(first / 'source-manifest.json'))
for item in manifest['files']:
    assert hashlib.sha256(read(ROOT / item['path'])).hexdigest() == item['sha256'], item['path']
run.mkdir()
(run / 'sources').mkdir()
for item in manifest['files']:
    (run / item['frozen']).write_bytes(read(first / item['frozen']))
for name in ('source-manifest.json', 'inputs.json'):
    (run / name).write_bytes(read(first / name))
for name, value in (
    ('scope-before.json', data_scope()), ('provider-before.json', provider_snapshot()),
    ('comparison-plan.json', dict(toolAccess='DEFAULT', reports=1, counterpart=first.name,
         fixedOrder=['NONE', 'DEFAULT'], alreadyExposedInputs=True, maxInvokerOperations=3,
         sameCurrentCode=True, crossModelComparisons=0, notFormalQualityOrPerformance=True))):
    (run / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(prepared=True, toolAccess='DEFAULT', reports=1)))
