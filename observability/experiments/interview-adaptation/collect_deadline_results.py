"""Collect fresh executions; never count copied stale XML after compilation failure."""
import json
import os
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
RUNS = ROOT / 'observability/experiments/voice-frame-pipeline/runs'


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


def save(run, value):
    if (run / 'artifacts.sha256.json').exists():
        return  # Frozen runs are read-only; derived collection belongs to this script/report.
    path = run / 'execution-summary.json'
    if path.exists():
        assert json.loads(read(path)) == value
    else:
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


results = {}
for version in ['baseline-20261005-r1'] + [f'candidate-20261005-r{i}' for i in range(1, 7)]:
    run = RUNS / ('evaluation-deadline-' + version)
    if version == 'candidate-20261005-r3':
        results[version] = json.loads(read(run / 'execution-summary.json'))
        assert results[version]['actualTestsExecuted'] == 0
        continue
    files = list(run.rglob('TEST-*.xml'))
    suites = [ET.fromstring(read(p)) for p in files]
    value = dict(suites=len(suites), **{k: sum(int(s.attrib.get(k, 0)) for s in suites)
                 for k in ['tests', 'failures', 'errors', 'skipped']})
    value['actualExternalModelCalls'] = 0
    value['wireTerminationObservations'] = [line.strip() for s in suites
        for line in (s.findtext('system-out') or '').splitlines()
        if line.startswith('controlled-wire-cancel ')]
    value['failedCases'] = [c.attrib['name'] for s in suites for c in s.findall('testcase')
                           if c.find('failure') is not None or c.find('error') is not None]
    save(run, value)
    results[version] = value
print(json.dumps(results, ensure_ascii=True))
