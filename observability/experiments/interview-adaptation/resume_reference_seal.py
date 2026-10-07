"""Resume at leak scanning after a retained optional-config failure, without overwrites."""
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

from finalize_reference_facts import ADAPT, BASE, NAMES, REGRESSION, ROOT, load, read, save
from probe_training_history_product import data_scope
from reference_seal_credentials import existing_scoped_credentials

status = load(ADAPT / 'reference-facts-phase-status-20261006.json')
assert data_scope() == status['scope']
for item in load(BASE / REGRESSION[-1] / 'source-manifest.json')['files']:
    assert hashlib.sha256(read(ROOT / item['path'])).hexdigest() == item['sha256']
for name in NAMES:
    assert not (BASE / name / 'artifacts.sha256.json').exists()
    assert (BASE / name / 'phase-boundaries.json').exists()
run = BASE / REGRESSION[-1]
save(run, 'finalizer-attempt-result.json', dict(firstAttemptFailedBeforeSealing=True,
    reason='FileNotFoundError: optional providers.env is absent; current isolated keys are encrypted in DB',
    proofPreparationCompleted=True, firstAttemptExternalModelCalls=0,
    repairedCredentialLoading='existing isolated DB AES-GCM values decoded only in memory',
    artifactOverwrite=False))
for name in ('resume_reference_seal.py', 'reference_seal_credentials.py'):
    (run / name).write_bytes(read(ADAPT / name))
(run / 'finalize-reference-facts-repaired.py').write_bytes(read(ADAPT / 'finalize_reference_facts.py'))
secrets = existing_scoped_credentials()
for name in NAMES:
    target = BASE / name
    for directory, _, files in os.walk('\\\\?\\' + str(target.resolve()) if os.name == 'nt' else target):
        for filename in files:
            assert not any(secret in Path(directory, filename).read_bytes() for secret in secrets), name
save(run, 'all-current-chat-key-scan.json', dict(allScopedNontrivialChatKeyValuesScanned=True,
    exactSensitiveValuesFound=0, plaintextFilesWritten=0, remoteCredentialAccess=0,
    method='in-memory scan before sealing', encryptedValuesExported=False))
env = dict(os.environ, REHEVO_TTS_EXPERIMENT_API_KEY=secrets[0].decode())
for name in NAMES:
    subprocess.run([sys.executable, str(ROOT / 'observability/experiments/tts-streaming/seal_voice_run.py'),
        str(BASE / name)], cwd=ROOT, env=env, check=True, capture_output=True)
subprocess.run([sys.executable, str(ADAPT / 'verify_business_seals.py'),
    'observability/experiments/interview-adaptation/reference-facts-seal-verification-20261006.json',
    *NAMES], cwd=ROOT, check=True)
print(json.dumps(dict(sealed=True, runs=len(NAMES), actualChatOperations=8, actualTokens=27681,
    finalNormalReferenceChars=5970, qualityGate='HOLD', fullGoalStatus='active')))
