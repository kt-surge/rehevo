"""Independent full-file hashes, exact member set, and credential-value scan for this phase."""
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNS = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
NAMES = ('training-target-baseline-20261005-r1', 'training-target-candidate-20261005-r1',
         'training-target-candidate-20261005-r2', 'training-target-live-candidate-20261005-r1',
         'training-target-live-baseline-20261005-r1')
secret = os.environ['REHEVO_TTS_EXPERIMENT_API_KEY'].encode()
assert secret


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


results = []
for name in NAMES:
    run = RUNS / name
    manifest = json.loads(read(run / 'artifacts.sha256.json'))
    mismatches, leaks = [], []
    for file, expected in manifest['files'].items():
        raw = read(run / file)
        if hashlib.sha256(raw).hexdigest() != expected:
            mismatches.append(file)
        if secret in raw:
            leaks.append(file)
    path = '\\\\?\\' + str(run.resolve()) if os.name == 'nt' else str(run.resolve())
    actual = {Path(str(Path(directory, file)).removeprefix('\\\\?\\')).relative_to(run).as_posix()
              for directory, _, files in os.walk(path) for file in files}
    expected = set(manifest['files']) | {'artifacts.sha256.json', 'seal-verification.json'}
    members = sorted(actual ^ expected)
    results.append(dict(run=name, files=len(manifest['files']), hashMismatches=mismatches,
                        memberMismatches=members, exactSensitiveValuesFound=len(leaks)))
result = dict(verifiedAt=datetime.now(timezone.utc).isoformat(), runs=results,
              totalFiles=sum(run['files'] for run in results), method='independent hashes and os.walk long-path membership')
output = Path(__file__).with_name('training-target-seal-verification-20261005.json')
assert not output.exists()
output.write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result, indent=2))
assert all(not item['hashMismatches'] and not item['memberMismatches']
           and item['exactSensitiveValuesFound'] == 0 for item in results)
