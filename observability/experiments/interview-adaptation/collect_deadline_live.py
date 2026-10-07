"""Preserve one actual normal model preflight and unchanged controlled data scope."""
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).parent))
from probe_training_history_product import data_scope


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


def save(run, name, value):
    assert not (run / 'artifacts.sha256.json').exists()
    path = run / name
    if path.exists():
        assert json.loads(read(path)) == json.loads(json.dumps(value)), name
    else:
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


run = ROOT / 'observability/experiments/voice-frame-pipeline/runs/evaluation-deadline-live-candidate-20261005-r1'
report = json.loads(read(run / 'report.json'))
inputs = json.loads(read(run / 'inputs.json'))
parsed = json.loads(read(run / '01-parsed-result.json'))
original = {q['questionIndex']: q for q in inputs['qaRecords']}
assert [q['questionIndex'] for q in parsed['questionEvaluations']] == [0, 1]
assert report['scoredQuestions'] == 1 and report['failedQuestions'] == 0
assert report['questionDetails'][1]['evaluationStatus'] == 'UNANSWERED'
assert all(task['questionIndexes'] and set(task['questionIndexes']) == {0}
           for task in report['trainingTasks'])
assert all(s and s in original[0]['userAnswer'] for s in report['questionDetails'][0]['answerEvidence'])
scope = data_scope()
assert json.loads(read(run / 'scope-before.json')) == scope
usage = dict(input=0, output=0, total=0, observedChatOperations=0)
for meter in json.loads(read(run / 'meters-after.json')):
    tags = {x['key']: x['value'] for x in meter['tags']}
    values = {x['statistic']: x['value'] for x in meter['measurements']}
    if tags.get('gen_ai.operation.name') != 'chat':
        continue
    if meter['name'] == 'gen_ai.client.token.usage':
        usage[tags['gen_ai.token.type']] += values['COUNT']
    elif meter['name'] == 'gen_ai.client.operation':
        usage['observedChatOperations'] += values['COUNT']
    elif meter['name'] == 'gen_ai.client.operation.active':
        assert values['ACTIVE_TASKS'] == 0
assert usage['total'] == usage['input'] + usage['output']
usage.update(crossModelComparisons=0, embedding=0, asr=0, tts=0,
             supplierWireRequests='not captured; SDK operations are not HTTP request counts')
save(run, 'scope-after.json', scope)
save(run, 'usage-summary.json', usage)
save(run, 'semantic-audit.json', dict(rawIndexes=[0, 1], evidenceFromCurrentAnswer=True,
    unansweredStatus='UNANSWERED', taskIndexesOnlyScored=True, normalCompatibilityPreflightPassed=True,
    qualityGate='HOLD', notFormalLatencyOrAccuracy=True,
    semanticLimit='Task explanations still infer unfamiliarity from unverified knowledge and mention '
        'an unanswered follow-up; stable competency identifiers and broader grounded-quality gates remain incomplete'))
old = ROOT / 'observability/experiments/voice-frame-pipeline/runs/evaluation-closed-preflight-20261005-r1'
left = json.loads(read(old / '01-supplied-prompt.json'))
right = json.loads(read(run / '01-supplied-prompt.json'))
assert left['system'] == right['system'] and left['user'] == right['user']
save(run, 'normal-input-integrity.json', dict(firstSystemIdentical=True, firstUserIdentical=True,
    inputByteIdentical=read(old / 'inputs.json') == read(run / 'inputs.json'),
    firstUserSha256=hashlib.sha256(right['user'].encode()).hexdigest(),
    notRandomPairedExperiment=True, noNormalLatencyBenefitClaim=True))
print(json.dumps(dict(mechanicalPreflightPassed=True, qualityGate='HOLD', usage=usage)))
