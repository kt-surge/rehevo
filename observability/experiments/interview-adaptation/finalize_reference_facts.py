"""Verify the distinct inference/loading variants, then seal immutable evidence."""
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

from probe_training_history_product import data_scope, provider_snapshot
from reference_seal_credentials import existing_scoped_credentials

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
ADAPT = Path(__file__).parent
SOURCES = ['reference-facts-source-baseline-20261006-r1'] + [
    f'reference-facts-source-candidate-20261006-r{i}' for i in range(1, 5)]
LIVE = [f'reference-facts-live-{case}-{arm}-20261006-r1'
        for case in ('boot-config', 'spring-proxy', 'mysql-order', 'mq-commit')
        for arm in ('baseline', 'candidate')]
REGRESSION = [f'reference-facts-regression-20261006-r{i}' for i in range(1, 5)]
NAMES = SOURCES + LIVE + REGRESSION


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


def load(path):
    return json.loads(read(path))


def save(run, name, value):
    assert not (run / 'artifacts.sha256.json').exists() and not (run / name).exists()
    (run / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def main():
    scope = data_scope()
    current_providers = provider_snapshot()
    provider_value = {k:v for k,v in current_providers.items() if k != 'capturedAt'}
    usage = dict(input=0, output=0, total=0, observedChatOperations=0)
    for name in LIVE:
        run = BASE / name
        assert load(run / 'scope-before.json') == load(run / 'scope-after.json') == scope
        provider = {k:v for k,v in load(run / 'provider-before.json').items() if k != 'capturedAt'}
        assert provider == provider_value, name
        audit = load(run / 'mechanical-audit.json')
        assert audit['typeMatches'] and audit['targetIdsValid'] and audit['rawRubricLevels'] == 5
        integrity = load(run / 'comparison-integrity.json')
        assert integrity['onlyActualReferenceSectionDiffers'] and integrity['inputByteIdentical']
        values = load(run / 'usage-summary.json')
        for key in usage:
            usage[key] += values[key]
    assert usage == dict(input=22277, output=5404, total=27681, observedChatOperations=8)
    context_versions = []
    for index, name in enumerate(REGRESSION):
        run = BASE / name
        tests = load(run / 'test-summary.json')
        assert tests['tests'] == 461 and tests['suites'] == 92 and tests['buildSuccessful']
        assert tests['failures'] == tests['errors'] == tests['skipped'] == 0
        context = load(run / 'normal-reference-context-audit.json')
        old, new = context['baseline'], context['candidate']
        assert old['evaluationTruncated'] and old['generationChars'] == 6343
        body = new['evaluationReference'].split('(SYSTEM_DESIGN_SCENARIO)\n', 1)[1]
        suffix_chars = len(body.removesuffix('\n...（references 已截断）'))
        assert suffix_chars == [74, 1260, 1491, 1539][index]
        assert new['generationChars'] == [7465, 6279, 6048, 5970][index]
        context_versions.append(dict(variant=f'r{index+1}', fullReferenceChars=new['generationChars'],
            evaluationTruncated=new['evaluationTruncated'], systemDesignVisibleChars=suffix_chars))
    final = load(BASE / REGRESSION[-1] / 'normal-reference-context-audit.json')['candidate']
    assert final['generationReference'] == final['evaluationReference'] and not final['evaluationTruncated']
    assert 'protected/package-visible' in final['evaluationReference']
    assert 'publicMethodsOnly' in final['evaluationReference'] and '外层事务' in final['evaluationReference']
    assert 'Index Merge' in final['evaluationReference'] and '唯一键等值' in final['evaluationReference']
    for item in load(BASE / REGRESSION[-1] / 'source-manifest.json')['files']:
        assert hashlib.sha256(read(ROOT / item['path'])).hexdigest() == item['sha256'], item['path']
    runtime = load(ADAPT / 'reference-facts-runtime-verification-20261006.json')
    assert runtime['health']['status'] == 'UP' and runtime['manifestWorkspaceMatched']
    assert all(item['lastWriteBeforeStart'] for item in runtime['classFilesBeforeProcessStart'])
    report = read(ADAPT / 'REFERENCE_FACTS_RESULTS_2026-10-06.md')
    proof = dict(usage=usage, crossModelOperations=0, originalCrossModelAuthorizationReused=False,
        sourceVariants=len(SOURCES), actualInferenceRuns=8, regressionAndLoadingRuns=len(REGRESSION),
        finalVariant='r4', finalVariantLiveInferenceRuns=0, allInferenceResultsApplyTo='r1',
        contextVersions=context_versions, currentBackendSourcesMatchFinalRegression=True,
        defaultProviderUnchanged=True, runtime=runtime, scope=scope,
        qualityGate='HOLD', formalRagAndVoiceGatesUnchanged=True,
        fullGoalStatus='active', noIndependentHumanLabels=True,
        sdkWireRequests='not captured; original retries retained')
    save(ADAPT, 'reference-facts-phase-status-20261006.json', proof)
    for name in NAMES:
        run = BASE / name
        save(run, 'phase-boundaries.json', dict(finalVariant='r4', thisRun=name,
            r1InferenceDoesNotDescribeR4=True, fullGoalStatus='active', semanticQualityGate='HOLD'))
        (run / 'reviewed-results.md').write_bytes(report)
    final_run = BASE / REGRESSION[-1]
    for name in ('reference-facts-pre-restart-owner-20261006.json',
                 'reference-facts-runtime-verification-20261006.json', 'reference-facts-phase-status-20261006.json'):
        (final_run / name).write_bytes(read(ADAPT / name))
    save(final_run, 'runtime-restart-attempts.json', dict(firstStartFailedBeforeLaunch=True,
        reason='old Gradle session still held boot-run.log', oldAppSession=67131,
        oldAppPid=54592, oldSessionTerminatedBeforeRetry=True, portUnownedBeforeRetry=True,
        restoredSession=39512, restoredPid=runtime['processId'], finalHealth=runtime['health']))
    for name in ('compact_reference_texts.py', 'finalize_reference_facts.py', 'verify_business_seals.py'):
        (final_run / name).write_bytes(read(ADAPT / name))
    (final_run / 'seal_voice_run.py').write_bytes(read(ROOT / 'observability/experiments/tts-streaming/seal_voice_run.py'))
    # Read existing API-key values only into memory for exact-value leak scans.
    secrets = existing_scoped_credentials()
    for name in NAMES:
        root = BASE / name
        for directory, _, files in os.walk('\\\\?\\' + str(root.resolve()) if os.name == 'nt' else root):
            for filename in files:
                assert not any(value in Path(directory, filename).read_bytes() for value in secrets), name
    env = dict(os.environ, REHEVO_TTS_EXPERIMENT_API_KEY=secrets[0].decode())
    for name in NAMES:
        subprocess.run([sys.executable, str(ROOT / 'observability/experiments/tts-streaming/seal_voice_run.py'),
            str(BASE / name)], cwd=ROOT, env=env, check=True, capture_output=True)
    subprocess.run([sys.executable, str(ADAPT / 'verify_business_seals.py'),
        'observability/experiments/interview-adaptation/reference-facts-seal-verification-20261006.json',
        *NAMES], cwd=ROOT, check=True)
    print(json.dumps(dict(sealed=True, runs=len(NAMES), usage=usage,
        finalNormalReferenceChars=final['evaluationChars'], fullGoalStatus='active', qualityGate='HOLD')))


if __name__ == '__main__':
    main()
