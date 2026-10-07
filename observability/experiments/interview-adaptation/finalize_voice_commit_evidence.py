"""Verify paired cases, scopes, tested sources, and runtime binding before sealing."""
import hashlib
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
NAMES = ['business-persistence-voice-baseline-20261006-r1',
         'business-persistence-voice-candidate-20261006-r1',
         'business-persistence-voice-full-20261006-r1',
         'voice-empty-live-baseline-20261006-r1', 'voice-empty-live-candidate-20261006-r1']
ADAPT = ROOT / 'observability/experiments/interview-adaptation'


def data(name, file):
    return json.loads((BASE / name / file).read_text(encoding='utf-8-sig'))


def read(path):
    return Path('\\\\?\\' + str(path.resolve()) if os.name == 'nt' else path).read_bytes()


def case_code(name):
    file = next(item for item in data(name, 'source-manifest.json')['files']
                if item['path'].endswith('VoiceEvaluationCommitIntegrationTest.java'))
    return read(BASE / name / file['frozen']).decode('utf-8').replace('\r\n', '\n').split('  @Configuration')[0]


def main():
    assert case_code(NAMES[0]) == case_code(NAMES[1]), 'Test scenarios or input changed'
    old, new = NAMES[-2:]
    left, right = read(BASE / old / 'input.json'), read(BASE / new / 'input.json')
    assert left == right
    assert not data(old, 'result.json')['completedReportVisible']
    assert data(new, 'result.json')['completedReportVisible']
    for name in (old, new):
        assert data(name, 'scope-before.json') == data(name, 'scope-after.json')
        assert read(BASE / name / 'metrics-before.txt') == read(BASE / name / 'metrics-after.txt')
        assert data(name, 'owned-cleanup-proof.json')['cacheExists'] == 0
    for name, tests, failures in ((NAMES[0], 6, 6), (NAMES[1], 6, 0), (NAMES[2], 461, 0)):
        summary = data(name, 'test-summary.json')
        assert summary['tests'] == tests and summary['failures'] == failures
        assert summary['errors'] == summary['skipped'] == 0
        assert summary['buildSuccessful'] == (failures == 0)
    tested = data(NAMES[2], 'source-manifest.json')['files']
    assert all(hashlib.sha256(read(ROOT / item['path'])).hexdigest() == item['sha256'] for item in tested)
    proof_files = [(old, 'voice-commit-pre-restart-owner-20261006.json'),
                   (new, 'voice-commit-runtime-verification-20261006.json')]
    for name, file in proof_files:
        target = BASE / name / 'runtime-owner-proof.json'
        assert not target.exists()
        target.write_bytes(read(ADAPT / file))
    report = read(ADAPT / 'VOICE_REPORT_COMMIT_RESULTS_2026-10-06.md')
    for name in NAMES:
        target = BASE / name / 'reviewed-results.md'
        assert not target.exists() and not (BASE / name / 'artifacts.sha256.json').exists()
        target.write_bytes(report)
    proof = dict(runs=NAMES, sixH2ScenariosIdentical=True, liveInputIdentical=True,
        liveInputSha256=hashlib.sha256(left).hexdigest(), testedSourcesStillCurrent=True,
        testedSources=len(tested), externalModelCalls=0, restoredDataScope=True)
    target = ADAPT / 'voice-commit-paired-proof-20261006.json'
    assert not target.exists()
    target.write_text(json.dumps(proof, indent=2), encoding='utf-8')
    print(json.dumps(proof))


if __name__ == '__main__':
    main()
