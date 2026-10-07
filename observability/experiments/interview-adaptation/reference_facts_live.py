"""Prepare and mechanically audit immutable single-category reference A/B runs."""
import hashlib
import json
import os
import re
import sys
from pathlib import Path

from probe_training_history_product import data_scope, provider_snapshot

ROOT = Path(__file__).resolve().parents[3]
RUNS = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
CASES = {'boot-config': 'AB', 'spring-proxy': 'BA', 'mysql-order': 'AB', 'mq-commit': 'BA'}


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


def load(run, name):
    return json.loads(read(run / name))


def save(run, name, value):
    assert not (run / 'artifacts.sha256.json').exists()
    path = run / name
    assert not path.exists(), name
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def source(arm):
    return RUNS / f'reference-facts-source-{arm}-20261006-r1'


def target(case, arm):
    return RUNS / f'reference-facts-live-{case}-{arm}-20261006-r1'


def normalize(text):
    return re.sub(r'data-boundary-[0-9a-f]{8}-', 'data-boundary-FROZEN-', text).replace('\r\n', '\n')


def usage(run):
    result = dict(input=0, output=0, total=0, observedChatOperations=0)
    for meter in load(run, 'meters-after.json'):
        tags = {item['key']: item['value'] for item in meter['tags']}
        values = {item['statistic']: item['value'] for item in meter['measurements']}
        if tags.get('gen_ai.operation.name') != 'chat':
            continue
        assert tags.get('gen_ai.request.model') in (None, 'qwen3.8-flash'), tags
        if meter['name'] == 'gen_ai.client.token.usage':
            result[tags['gen_ai.token.type']] += values['COUNT']
        elif meter['name'] == 'gen_ai.client.operation':
            result['observedChatOperations'] += values['COUNT']
        elif meter['name'] == 'gen_ai.client.operation.active':
            assert values['ACTIVE_TASKS'] == 0
    assert result['input'] + result['output'] == result['total']
    result.update(crossModelComparisons=0, embedding=0, asr=0, tts=0,
        supplierWireRequests='not captured; SDK operation counts are not wire request counts')
    return result


def prepare(case, arm):
    assert case in CASES and arm in ('baseline', 'candidate')
    # Check previous observed usage before starting another operation.
    completed = [p for p in RUNS.glob('reference-facts-live-*-20261006-r1')
                 if (p / 'meters-after.json').exists()]
    totals = [usage(p) for p in completed]
    assert len(completed) < 8 and sum(item['total'] for item in totals) < 30000
    scope = data_scope()
    providers = provider_snapshot()
    defaults = [p['model'] for p in providers['providers'] if p.get('defaultChatProvider')]
    assert defaults == ['qwen3.8-flash'], defaults
    frozen = source(arm)
    manifest = load(frozen, 'source-manifest.json')
    for entry in manifest['files']:
        assert hashlib.sha256(read(frozen / entry['frozen'])).hexdigest() == entry['sha256']
        if not entry['path'].endswith(('/spring.md', '/mysql.md', '/mq.md')):
            assert hashlib.sha256(read(ROOT / entry['path'])).hexdigest() == entry['sha256'], entry['path']
    run = target(case, arm)
    run.mkdir()
    (run / 'sources').mkdir()
    for entry in manifest['files']:
        (run / entry['frozen']).write_bytes(read(frozen / entry['frozen']))
    (run / 'input.json').write_bytes(read(source('baseline') / 'inputs' / f'{case}.json'))
    save(run, 'source-manifest.json', manifest)
    save(run, 'scope-before.json', scope)
    save(run, 'provider-before.json', providers)
    save(run, 'run-plan.json', dict(case=case, arm=arm, order=CASES[case], model='qwen3.8-flash',
        sourceVariant=frozen.name, maxInvokerOperations=1, structuredMaxAttempts=1,
        followUps=0, observedTokensBefore=sum(item['total'] for item in totals),
        singleCategoryFixture=True, exposedDevelopmentDiagnostic=True, realResumeOrJd=False,
        actualProductSessionWrites=0, crossModelComparisons=0,
        observedTokenStopThreshold=30000, supplierWireRequestLimit='original SDK retries, not captured'))
    # Launcher/auditor are reproducibility evidence, added after source variation freeze.
    for name in ('run_reference_facts.ps1', 'reference_facts_live.py'):
        (run / name).write_bytes(read(Path(__file__).parent / name))
    print(json.dumps(dict(prepared=True, run=run.name, previousObservedTokens=sum(x['total'] for x in totals))))


def collect(case):
    scope = data_scope()
    reports = {}
    texts = {}
    refs = {}
    for arm in ('baseline', 'candidate'):
        run = target(case, arm)
        assert load(run, 'scope-before.json') == scope
        config = load(run, 'runtime-config.json')
        assert config['singleCategoryFixture'] and config['noSessionWrites']
        result = load(run, 'result.json')
        assert result['status'] == 'finished' and result['invokerOperations'] == 1
        consumed = usage(run)
        assert consumed['observedChatOperations'] == 1
        prompt = load(run, 'supplied-prompt.json')
        reference = normalize(load(run, 'reference-context.json')['referenceSection'])
        user = normalize(prompt['user'])
        assert user.count(reference) == 1, 'actual reference not present exactly once'
        texts[arm] = dict(system=normalize(prompt['system']),
                         user=user.replace(reference, '<FROZEN_REFERENCE_SECTION>'),
                         suffix=normalize(prompt['securitySuffix']))
        refs[arm] = reference
        questions = load(run, 'questions.json')
        raw = load(run, 'parsed-result.json')
        plan = load(run, 'plan.json')
        input_value = load(run, 'input.json')
        assert len(questions) == 1 and len(raw['questions']) == 1
        question = questions[0]
        allowed = {t['targetId'] for t in plan['trainingTargets']}
        target_ids = question['evaluationGuide'].get('trainingTargetIds') or []
        mechanical = dict(mainQuestions=1, followUps=0,
            requestedCategory=input_value['categoryKey'], actualType=question['type'],
            typeMatches=question['type'] == input_value['categoryKey'],
            trainingTargetIds=target_ids, targetIdsValid=len(target_ids) <= 1 and set(target_ids).issubset(allowed),
            rawRubricLevels=len(raw['questions'][0].get('rubric') or []),
            actualReferenceChars=len(reference), actualProductSessionWrites=0,
            semanticQualityGate='HOLD pending separate semantic and fixed-answer scoring review',
            scopeUnchanged=True, formalEffectOrLatency=False)
        save(run, 'usage-summary.json', consumed)
        save(run, 'mechanical-audit.json', mechanical)
        save(run, 'scope-after.json', scope)
        reports[arm] = dict(usage=consumed, mechanical=mechanical, result=result)
    assert read(target(case, 'baseline') / 'input.json') == read(target(case, 'candidate') / 'input.json')
    assert texts['baseline'] == texts['candidate'], 'non-reference prompt differs'
    assert refs['baseline'] != refs['candidate']
    integrity = dict(inputByteIdentical=True, promptOutsideReferenceEqual=True,
        systemAndSecuritySuffixEqual=True, onlyActualReferenceSectionDiffers=True,
        randomBoundaryAndCrlfOnlyNormalization=True, case=case, order=CASES[case],
        exposedDevelopmentDiagnostic=True, noFormalQualityOrLatencyClaim=True)
    for arm in ('baseline', 'candidate'):
        save(target(case, arm), 'comparison-integrity.json', integrity)
    output = ROOT / f'observability/experiments/interview-adaptation/reference-facts-{case}-summary-20261006.json'
    assert not output.exists()
    output.write_text(json.dumps(dict(reports=reports, integrity=integrity), ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(case=case, reports=reports, integrity=integrity)))


if __name__ == '__main__':
    if sys.argv[1] == 'prepare':
        prepare(sys.argv[2], sys.argv[3])
    elif sys.argv[1] == 'collect':
        collect(sys.argv[2])
    else:
        raise ValueError('Use prepare or collect')
