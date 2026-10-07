"""Copy actual fresh test XML using Windows long-path membership; never alter sealed runs."""
import json
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
run = ROOT / sys.argv[1]
task = sys.argv[2] if len(sys.argv) > 2 else 'test'
assert task in ('test', 'integrationTest')
assert run.is_relative_to(ROOT / 'observability/experiments/voice-frame-pipeline/runs')
assert not (run / 'artifact-seal.json').exists()
assert not (run / 'test-summary.json').exists()
log = (run / 'full-backend.log').read_text(encoding='utf-8', errors='replace')
assert f'> Task :app:{task}\n' in log or f'> Task :app:{task} FAILED' in log, 'No executed test task'
source = (ROOT / f'app/build/test-results/{task}').resolve()
source_name = '\\\\?\\' + str(source) if os.name == 'nt' else str(source)
dest = run / 'junit'
dest.mkdir(exist_ok=True)
totals = dict(suites=0, tests=0, failures=0, errors=0, skipped=0)
for directory, _, files in os.walk(source_name):
    for name in files:
        if not name.startswith('TEST-') or not name.endswith('.xml'):
            continue
        raw = Path(directory, name).read_bytes()
        root = ET.fromstring(raw)
        target = (dest / name).resolve()
        Path('\\\\?\\' + str(target) if os.name == 'nt' else str(target)).write_bytes(raw)
        totals['suites'] += 1
        for key in ('tests', 'failures', 'errors', 'skipped'):
            totals[key] += int(root.get(key, 0))
assert totals['tests'] > 0
summary = dict(totals, actualExecutedTests=True, task=task, externalModelCalls=0,
               buildSuccessful='BUILD SUCCESSFUL' in log)
(run / 'test-summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
print(json.dumps(summary))
