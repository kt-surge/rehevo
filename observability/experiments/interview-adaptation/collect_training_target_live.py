"""Mechanical audit and actual default-model usage for the single development counterpart."""
import hashlib
import json
import os
import re
from pathlib import Path

from probe_training_history_product import data_scope

ROOT = Path(__file__).resolve().parents[3]
RUNS = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
scope = data_scope()


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


def load(run, name):
    return json.loads(read(run / name))


def save(run, name, value):
    assert not (run / 'artifacts.sha256.json').exists()
    path = run / name
    assert not path.exists(), name
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


summary = {}
prompts = {}
for arm in ('candidate', 'baseline'):
    run = RUNS / f'training-target-live-{arm}-20261005-r1'
    assert load(run, 'scope-before.json') == scope
    config = load(run, 'runtime-config.json')
    assert ('training-target/baseline/' in config['loadedQuestionClass']) == (arm == 'baseline')
    assert ('training-target/baseline/' in config['loadedPlanClass']) == (arm == 'baseline')
    questions = load(run, 'questions.json')
    main = [q for q in questions if not q['isFollowUp']]
    plan = load(run, 'plan.json')
    inputs = load(run, 'inputs.json')
    prompts[arm] = load(run, '01-supplied-prompt.json')
    allowed_types = {'JAVA', 'MYSQL', 'REDIS', 'SPRING', 'SYSTEM_DESIGN_SCENARIO', 'PROJECT'}
    invalid_types = [dict(index=q['questionIndex'], type=q['type'], category=q['category'])
                     for q in main if q['type'] not in allowed_types]
    allowed_targets = {target['targetId'] for target in plan.get('trainingTargets', [])}
    unknown_ids = [dict(index=q['questionIndex'], ids=q['evaluationGuide'].get('trainingTargetIds'))
                   for q in main if len(q['evaluationGuide'].get('trainingTargetIds', [])) > 1
                   or not set(q['evaluationGuide'].get('trainingTargetIds', [])).issubset(allowed_targets)]
    usage = dict(input=0, output=0, total=0, observedChatOperations=0)
    for meter in load(run, 'meters-after.json'):
        tags = {x['key']: x['value'] for x in meter['tags']}
        values = {x['statistic']: x['value'] for x in meter['measurements']}
        if tags.get('gen_ai.operation.name') != 'chat':
            continue
        assert tags.get('gen_ai.request.model') in (None, 'qwen3.8-flash')
        if meter['name'] == 'gen_ai.client.token.usage':
            usage[tags['gen_ai.token.type']] += values['COUNT']
        elif meter['name'] == 'gen_ai.client.operation':
            usage['observedChatOperations'] += values['COUNT']
        elif meter['name'] == 'gen_ai.client.operation.active':
            assert values['ACTIVE_TASKS'] == 0
    assert usage['input'] + usage['output'] == usage['total']
    usage.update(crossModelComparisons=0, embedding=0, asr=0, tts=0,
                 supplierWireRequests='not captured; SDK operations are not wire request counts')
    mechanical = dict(mainQuestions=len(main), requestedTargets=plan['requestedFocusCompetencies'],
                      arrangedTargets=plan['prioritizedCompetencies'], invalidTypes=invalid_types,
                      unknownOrMultipleTargets=unknown_ids, actualProductSessionWrites=0,
                      scopeUnchanged=True, semanticQualityGate='HOLD', populationEffectClaim=False)
    save(run, 'usage-summary.json', usage)
    save(run, 'mechanical-audit.json', mechanical)
    save(run, 'scope-after.json', scope)
    summary[arm] = dict(result=load(run, 'result.json'), usage=usage, mechanical=mechanical)


def normalize(text):
    return re.sub(r'data-boundary-[0-9a-f]{8}-', 'data-boundary-FROZEN-', text).replace('\r\n', '\n')


before_training = [normalize(prompts[arm]['user']).split('## 历史训练重点（优先复测）')[0]
                   for arm in ('candidate', 'baseline')]
references_and_output = [normalize(prompts[arm]['user']).split('## 参考题库（references）', 1)[1]
                         for arm in ('candidate', 'baseline')]
persona = [normalize(prompts[arm]['system']).split('# Skill Persona', 1)[1]
           .split('Your response should be in JSON format.', 1)[0] for arm in ('candidate', 'baseline')]
assert before_training[0] == before_training[1]
assert references_and_output[0] == references_and_output[1]
assert persona[0] == persona[1]
assert read(RUNS / 'training-target-live-candidate-20261005-r1/inputs.json') == read(
    RUNS / 'training-target-live-baseline-20261005-r1/inputs.json')
integrity = dict(pairs=1, fixedOrder=['candidate', 'baseline'], alreadyExposed=True,
                 inputByteIdentical=True, commonInputBeforeTrainingEqual=True,
                 referenceAndOutputInstructionsEqual=True, personaAndGenericModeEqual=True,
                 normalization='random data boundary markers and CRLF only',
                 noFormalQualityOrLatencyClaim=True)
for arm in ('candidate', 'baseline'):
    save(RUNS / f'training-target-live-{arm}-20261005-r1', 'comparison-integrity.json', integrity)
output = ROOT / 'observability/experiments/interview-adaptation/training-target-live-summary-20261005.json'
assert not output.exists()
output.write_text(json.dumps(dict(summary=summary, integrity=integrity), ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(dict(usage={arm: summary[arm]['usage'] for arm in summary}, integrity=integrity)))
