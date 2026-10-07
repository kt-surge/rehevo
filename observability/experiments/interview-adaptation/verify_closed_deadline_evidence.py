"""Independent byte/membership check of current and preceding sealed evaluation evidence."""
import hashlib
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNS = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
names = [
    'training-history-baseline-20261005-r1', 'training-history-candidate-20261005-r1',
    'training-history-product-20261005-r1',
    'evaluation-grounding-baseline-20261005-r1', 'evaluation-grounding-candidate-20261005-r1',
    'evaluation-grounding-candidate-20261005-r2', 'evaluation-grounding-live-baseline-20261005-r1',
    'evaluation-grounding-live-candidate-20261005-r1',
    'structured-retry-baseline-20261005-r1', 'structured-retry-baseline-20261005-r2',
    'structured-retry-baseline-20261005-r3', 'structured-retry-candidate-20261005-r1',
    'evaluation-closed-preflight-20261005-r1', 'evaluation-closed-default-20261005-r1',
    'evaluation-deadline-baseline-20261005-r1',
] + [f'evaluation-deadline-candidate-20261005-r{i}' for i in range(1, 7)] + [
    'evaluation-deadline-live-candidate-20261005-r1']


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


sensitive = [os.environ.get(name, '').encode() for name in
             ['REHEVO_TTS_EXPERIMENT_API_KEY', 'AI_BAILIAN_API_KEY', 'ALI-API-KEY']]
sensitive = [value for value in sensitive if value]
assert sensitive, 'Exact credential-value scan requires the existing process credential; never exported'
versions = []
for name in names:
    run = RUNS / name
    hashes = json.loads(read(run / 'artifacts.sha256.json'))['files']
    walk_root = '\\\\?\\' + str(run.resolve()) if os.name == 'nt' else str(run)
    actual = {Path(str(Path(directory) / file).removeprefix('\\\\?\\')).relative_to(run).as_posix()
              for directory, _, files in os.walk(walk_root) for file in files}
    assert actual == set(hashes) | {'artifacts.sha256.json', 'seal-verification.json'}, name
    for file, expected in hashes.items():
        raw = read(run / file)
        assert hashlib.sha256(raw).hexdigest() == expected, (name, file)
        assert not any(value in raw for value in sensitive), 'Exact credential value found'
    versions.append(dict(run=name, files=len(hashes)))
result = dict(versions=versions, totalVersions=len(versions), files=sum(x['files'] for x in versions),
              mismatches=0, extraFiles=0, exactSensitiveValuesFound=0)
path = Path(__file__).parent / 'closed-deadline-seal-verification-20261005.json'
assert not path.exists(), 'Preserve previous independent verification'
path.write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k != 'versions'}))
