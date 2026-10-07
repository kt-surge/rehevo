"""Check exact paired inputs and copy the reviewed report before sealing five runs."""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
NAMES = ['business-persistence-cache-baseline-20261005-r1',
         'business-persistence-cache-candidate-20261005-r1',
         'business-persistence-cache-integration-20261005-r1',
         'text-delete-live-baseline-20261005-r1', 'text-delete-live-candidate-20261005-r1']


def data(name, file):
    return json.loads((BASE / name / file).read_text(encoding='utf-8-sig'))


def main():
    old, new = NAMES[-2:]
    left, right = (BASE / old / 'input.json').read_bytes(), (BASE / new / 'input.json').read_bytes()
    assert left == right
    assert data(old, 'result.json')['deletedSessionStillReadable'] is True
    assert data(new, 'result.json')['deletedSessionStillReadable'] is False
    assert data(old, 'result.json')['afterCacheExists'] == 1
    assert data(new, 'result.json')['afterCacheExists'] == 0
    for name in (old, new):
        assert data(name, 'scope-before.json') == data(name, 'scope-after.json')
        assert (BASE / name / 'metrics-before.txt').read_bytes() == (BASE / name / 'metrics-after.txt').read_bytes()
    assert data(NAMES[0], 'test-summary.json')['failures'] == 3
    for name, count in ((NAMES[1], 461), (NAMES[2], 7)):
        summary = data(name, 'test-summary.json')
        assert summary['tests'] == count and summary['buildSuccessful']
        assert summary['failures'] == summary['errors'] == summary['skipped'] == 0
    report = (ROOT / 'observability/experiments/interview-adaptation/TEXT_DELETE_CACHE_RESULTS_2026-10-05.md').read_bytes()
    for name in NAMES:
        target = BASE / name / 'reviewed-results.md'
        assert not target.exists() and not (BASE / name / 'artifacts.sha256.json').exists()
        target.write_bytes(report)
    proof = dict(pairedInputIdentical=True, inputSha256=hashlib.sha256(left).hexdigest(),
                 runs=NAMES, externalModelCalls=0, restoredDataScope=True)
    target = ROOT / 'observability/experiments/interview-adaptation/text-cache-paired-proof-20261005.json'
    assert not target.exists()
    target.write_text(json.dumps(proof, indent=2), encoding='utf-8')
    print(json.dumps(proof))


if __name__ == '__main__':
    main()
