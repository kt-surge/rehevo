"""Seal the two executed type-contract regressions, then independently verify their members."""
import hashlib
import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNS = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
NAMES = ('training-target-type-baseline-20261005-r1', 'training-target-type-candidate-20261005-r1')
secret = os.environ['REHEVO_TTS_EXPERIMENT_API_KEY'].encode()
assert secret


def long(path):
    return Path('\\\\?\\' + str(path.resolve())) if os.name == 'nt' else path


def read(path):
    return long(path).read_bytes()


def create(path, raw):
    assert not long(path).exists(), path.name
    long(path).write_bytes(raw)


def save(path, value):
    create(path, json.dumps(value, ensure_ascii=False, indent=2).encode('utf-8'))


for name in NAMES:
    run = RUNS / name
    assert not (run / 'artifacts.sha256.json').exists()
    create(run / 'review-results.md', read(Path(__file__).with_name('QUESTION_TYPE_CONTRACT_RESULTS_2026-10-05.md')))
    audit = run / 'audit-sources'
    audit.mkdir()
    for file in ('collect_training_target_tests.py', 'seal_question_type_runs.py'):
        create(audit / file, read(Path(__file__).with_name(file)))
    save(run / 'model-call-boundary.json', dict(externalModelCalls=0,
        evidence='Mockito ChatClient/Registry/StructuredOutputInvoker for type assertions; full suite includes loopback SDK wire tests only'))
    subprocess.run([sys.executable, str(ROOT / 'observability/experiments/tts-streaming/seal_voice_run.py'), str(run)], check=True)
results = []
for name in NAMES:
    run = RUNS / name
    manifest = json.loads(read(run / 'artifacts.sha256.json'))
    mismatches = [file for file, expected in manifest['files'].items()
                  if hashlib.sha256(read(run / file)).hexdigest() != expected]
    leaks = [file for file in manifest['files'] if secret in read(run / file)]
    prefix = str(long(run))
    actual = {Path(str(Path(directory, file)).removeprefix('\\\\?\\')).relative_to(run).as_posix()
              for directory, _, files in os.walk(prefix) for file in files}
    expected = set(manifest['files']) | {'artifacts.sha256.json', 'seal-verification.json'}
    results.append(dict(run=name, files=len(manifest['files']), hashMismatches=mismatches,
        memberMismatches=sorted(actual ^ expected), exactSensitiveValuesFound=len(leaks)))
result = dict(verifiedAt=datetime.now(timezone.utc).isoformat(), runs=results,
              totalFiles=sum(item['files'] for item in results), method='independent SHA-256, full long-path membership, exact credential-value scan')
save(Path(__file__).with_name('question-type-seal-verification-20261005.json'), result)
print(json.dumps(result, indent=2))
assert all(not item['hashMismatches'] and not item['memberMismatches']
           and item['exactSensitiveValuesFound'] == 0 for item in results)
