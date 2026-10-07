"""Current scope/provider/source check and consolidated immutable phase status."""
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

from probe_training_history_product import data_scope, provider_snapshot

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).parent
RUNS = ROOT / 'observability/experiments/voice-frame-pipeline/runs'


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


scope = data_scope()
providers = provider_snapshot()
assert [p['model'] for p in providers['providers'] if p.get('defaultChatProvider')] == ['qwen3.8-flash']
assert providers['voice']['asr']['turnDetectionSilenceDurationMs'] == 2000
assert providers['voice']['asr']['model'] == 'qwen-audio-3.0-asr-flash-streaming'
assert providers['voice']['tts']['model'] == 'qwen-audio-3.1-tts-flash'
assert providers['voice']['tts']['voice'] == 'longanhuan_v3.1'
runtime = json.loads(read(HERE / 'training-target-runtime-verification-20261005.json').decode('utf-8-sig'))
tested = RUNS / 'training-target-type-candidate-20261005-r1'
source_matches = []
for item in json.loads(read(tested / 'source-manifest.json'))['files']:
    if not item['path'].startswith('app/'):
        continue
    assert hashlib.sha256(read(ROOT / item['path'])).hexdigest() == item['sha256'], item['path']
    source_matches.append(item['path'])
seals = [json.loads(read(HERE / file)) for file in ('training-target-seal-verification-20261005.json',
                                                   'question-type-seal-verification-20261005.json')]
sealed_runs = [run for seal in seals for run in seal['runs']]
assert len(sealed_runs) == 7 and all(not run['hashMismatches'] and not run['memberMismatches']
    and run['exactSensitiveValuesFound'] == 0 for run in sealed_runs)
status = dict(observedAt=datetime.now(timezone.utc).isoformat(), goalStatus='active',
    outcome='progress; original S0-S4 scope and mandatory gates preserved',
    semanticQualityGate='HOLD', sealedVersions=7, sealedFiles=sum(run['files'] for run in sealed_runs),
    finalBackendTests=454, skipped=0, matchedCurrentAppSources=source_matches,
    frontendBuildOrigin='training-target-candidate-20261005-r1; current two changed UI sources identical',
    defaultModelPreflightBeforeTypeFix=dict(sdkChatOperations=2, actualChatTokens=10760,
        fixedOrder=['candidate', 'baseline'], pairs=1, alreadyExposed=True, noFormalEffectClaim=True),
    typeFixExternalModelCalls=0, runtime=dict(runtime, execHandle=54840), scope=scope,
    providerSnapshot=providers, noAdditionalCrossModelAuthorizationUsed=True,
    outstanding=['question/Rubric technical correctness and current type-template live preflight',
        'complete text/voice evaluation-training-retest product flows',
        'known text DELETE cache and voice evaluation transaction boundaries',
        'formal browser voice samples and existing gates',
        'new-material RAG retrieval/generation/citation/cost gates', 'full required async fault matrix'])
output = HERE / 'training-target-phase-status-20261005.json'
assert not output.exists()
output.write_text(json.dumps(status, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(goalStatus=status['goalStatus'], semanticQualityGate=status['semanticQualityGate'],
    sealedVersions=status['sealedVersions'], sealedFiles=status['sealedFiles'], finalBackendTests=454,
    runtimePid=runtime['processId'], health=runtime['health']['status'], currentAppSourcesMatched=len(source_matches),
    counts=scope['counts'], actualChatTokens=10760, actualSdkChatOperations=2)))
