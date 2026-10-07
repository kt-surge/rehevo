"""New-only, bounded 27B calibration on public spans and synthetic answers. No generation."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

import citation_judge_v2 as prior

HERE = Path(__file__).resolve().parent
RUN = HERE / 'runs/term-judge-calibration-20261005-r4'
OLD = HERE / 'runs/citation-chain-20261005-r1'


def prepare():
    if RUN.exists():
        raise ValueError('Use a fresh run, preserve old evidence')
    old = prior.read(OLD / 'inputs.json')
    seal = prior.read(OLD / 'artifacts.sha256.json')['files']
    if prior.sha(OLD / 'inputs.json') != seal['inputs.json']:
        raise ValueError('Frozen source hash differs')
    cases = {case['id']: case for case in old['cases']}
    rd = cases['primary-dev-rd-04']['contexts']
    pg = cases['primary-dev-pg-01']['contexts']
    sp = cases['primary-dev-sp-01']['contexts']
    evidence = lambda rows: [dict(evidenceId=f'E{i+1}', text=row['body'],
        sourceUrl=row['sourceUrl'], sourceTextSha256=row['sourceTextSha256'])
        for i, row in enumerate(rows)]
    fixtures = [
        dict(id='mixed-wrong-background', answer='资料未给出生产重试秒数，不能确定。NOACK 会把普通新消息加入 PEL。',
            claim='NOACK 会把普通新消息加入 PEL。', supported=False, evidence=evidence(rd)),
        dict(id='mixed-correct-background', answer='资料未给出生产重试秒数，不能确定。NOACK 不把普通新消息加入 PEL。',
            claim='NOACK 不把普通新消息加入 PEL。', supported=True, evidence=evidence(rd)),
        dict(id='definition-absent', answer='NOACK 不把普通新消息加入 PEL（Pending Entries List）。',
            claim='NOACK 不把普通新消息加入 PEL（Pending Entries List）。', supported=False, evidence=evidence(rd)),
        dict(id='definition-present-control', answer='NOACK 不把普通新消息加入 PEL（Pending Entries List）。',
            claim='NOACK 不把普通新消息加入 PEL（Pending Entries List）。', supported=True,
            evidence=evidence(rd) + [dict(evidenceId='E3', text='PEL means Pending Entries List.',
                sourceUrl=None, sourceTextSha256=None, origin='explicit synthetic definition control, not quoted official text')]),
        dict(id='condition-contradiction', answer='Read Committed 的普通 SELECT 在查询开始时取快照，也能看到该查询执行期间其他事务新提交的数据。',
            claim='Read Committed 的普通 SELECT 在查询开始时取快照，也能看到该查询执行期间其他事务新提交的数据。',
            supported=False, evidence=evidence(pg)),
        dict(id='version-absent', answer='在 PostgreSQL 17 中，Read Committed 是默认隔离级别。',
            claim='在 PostgreSQL 17 中，Read Committed 是默认隔离级别。', supported=False, evidence=evidence(pg)),
        dict(id='version-explicit-control', answer='在 PostgreSQL 17 中，Read Committed 是默认隔离级别。',
            claim='在 PostgreSQL 17 中，Read Committed 是默认隔离级别。', supported=True,
            evidence=[dict(row, text='[Explicit source version: PostgreSQL 17]\n' + row['text'],
                metadataOrigin='frozen public manifest sourceVersion=17; body unchanged after this prefix')
                for row in evidence(pg)]),
        dict(id='proxy-condition-error', answer='资料未说明生产重试秒数。同一类内部调用 @Transactional 方法会被默认代理拦截并启动注解事务。',
            claim='同一类内部调用 @Transactional 方法会被默认代理拦截并启动注解事务。', supported=False, evidence=evidence(sp)),
    ]
    for item in fixtures:
        item['components'] = [dict(text=item['claim'], supported=item['supported'])]
        item['question'] = '仅依据本题供给片段判断回答。未供给的释义、版本、生产参数不得用外部知识补足。'
        item['claimStart'] = item['answer'].index(item['claim'])
        item['claimEnd'] = item['claimStart'] + len(item['claim'])
        item['assertionRetentionPolicy'] = 'All substantive characters of this frozen technical span must remain technical; missing-information text is separate.'
    components = {
        'definition-absent': [('NOACK 不把普通新消息加入 PEL。', True),
            ('PEL 的英文全称是 Pending Entries List。', False)],
        'definition-present-control': [('NOACK 不把普通新消息加入 PEL。', True),
            ('PEL 的英文全称是 Pending Entries List。', True)],
        'condition-contradiction': [('Read Committed 的普通 SELECT 在查询开始时取快照。', True),
            ('Read Committed 的普通 SELECT 能看到该查询执行期间其他事务新提交的数据。', False)],
        'version-absent': [('Read Committed 是 PostgreSQL 默认隔离级别。', True),
            ('这项默认隔离级别结论适用于 PostgreSQL 17。', False)],
        'version-explicit-control': [('Read Committed 是 PostgreSQL 默认隔离级别。', True),
            ('这项默认隔离级别结论适用于 PostgreSQL 17。', True)],
    }
    for item in fixtures:
        if item['id'] in components:
            item['components'] = [dict(text=text, supported=truth) for text, truth in components[item['id']]]
        item['componentReview'] = 'Agent-prepared explicit semantic components; not model-extracted or a production general decomposer.'
    RUN.mkdir()
    (RUN / 'calls').mkdir()
    for source in ('calibrate_term_judge.py', 'citation_judge_v2.py', 'RAG_TERM_BOUNDARY_DESIGN_2026-10-05.md'):
        shutil.copy2(HERE / source, RUN / source)
    prior.write(RUN / 'inputs.json', dict(origin='frozen public spans + explicit synthetic calibration controls',
        humanReviewed=False, review='Agent source audit; not independent human review', fixtures=fixtures))
    schedule = [(index, component) for index, item in enumerate(fixtures)
                for component in range(len(item['components']))]
    priority = [(5, 1), (6, 1), (2, 1)]
    schedule = priority + [item for item in schedule if item not in priority]
    plan = dict(model='qwen3.8-27b', maxExternalCalls=14, deadlineSeconds=60,
        inputsSha256=prior.sha(RUN / 'inputs.json'), sourceSha256=prior.sha(Path(__file__)),
        priorSourceSha256=prior.sha(HERE / 'citation_judge_v2.py'),
        partitionRubric=prior.PARTITION_RUBRIC, contextRubric=prior.CONTEXT_RUBRIC,
        partitionMaxCompletionTokens=1800, contextMaxCompletionTokens=600,
        contextResponseMode='json_object; original strict schema shape still verified locally',
        protocol='One fixed atomic component per request; source body only; no implicit default verdict for omitted tasks.',
        componentSchedule=schedule,
        scope='Evaluator calibration only, no application prompt, no new answer generation, no retrieval.',
        applicationEightCallAuthorizationReused=False)
    prior.write(RUN / 'plan.json', plan)
    print(json.dumps(dict(preparedCases=8, components=13, maximumCalls=14)))


def checked_plan():
    plan = prior.read(RUN / 'plan.json')
    if prior.sha(RUN / 'inputs.json') != plan['inputsSha256'] \
            or prior.sha(Path(__file__)) != plan['sourceSha256'] \
            or prior.sha(HERE / 'citation_judge_v2.py') != plan['priorSourceSha256']:
        raise ValueError('Frozen inputs/evaluator changed')
    return plan


def partition():
    plan = checked_plan()
    fixtures = prior.read(RUN / 'inputs.json')['fixtures']
    payload = dict(units=[dict(unitId=f['id'], text=f['answer']) for f in fixtures])
    prefix = RUN / 'calls/00-partition'
    prior.write(prefix.with_suffix('.payload.json'), payload)
    parsed = prior.call(prefix, plan, [dict(role='system', content=plan['partitionRubric']),
        dict(role='user', content=json.dumps(payload, ensure_ascii=False))],
        plan['partitionMaxCompletionTokens'], prior.response_schema('partition', payload))
    indexed = prior.indexed(parsed['units'], 'unitId', [f['id'] for f in fixtures])
    rows = []
    for fixture in fixtures:
        unit = dict(unitId=fixture['id'], text=fixture['answer'], start=0, citations=[])
        checked = prior.validate_partition(dict(answer=fixture['answer'], extraction=dict(units=[unit])),
            dict(units=[indexed[fixture['id']]]))
        assigned = ['non_claim'] * len(fixture['answer'])
        for part in checked['parts']:
            assigned[part['start']:part['end']] = [part['kind']] * (part['end'] - part['start'])
        required = [i for i in range(fixture['claimStart'], fixture['claimEnd'])
                    if fixture['answer'][i].isalnum()]
        omitted = [i for i in required if assigned[i] != 'technical']
        rows.append(dict(caseId=fixture['id'], requiredTechnicalSpanRetained=not omitted,
            nonTechnicalRequiredOffsets=omitted, partition=checked))
    prior.write(RUN / 'partition-reviewed.json', dict(rows=rows,
        allFrozenTechnicalSpansRetained=all(r['requiredTechnicalSpanRetained'] for r in rows),
        note='Frozen semantic retention checks; not proof of arbitrary answer decomposition.'))


def context(offset, limit):
    plan = checked_plan()
    fixtures = prior.read(RUN / 'inputs.json')['fixtures']
    schedule = plan['componentSchedule']
    if offset < 0 or limit < 1 or limit > 3 or offset + limit > len(schedule):
        raise ValueError('Require a bounded 1-3 case slice')
    if len(list((RUN / 'calls').glob('*.request.json'))) + limit > plan['maxExternalCalls']:
        raise ValueError('Frozen external call bound reached')
    for index in range(offset, offset + limit):
        fixture_index, component_index = schedule[index]
        fixture = fixtures[fixture_index]
        component = fixture['components'][component_index]
        component_id = f"{fixture['id']}-C{component_index+1}"
        payload = dict(question=fixture['question'], claims=[dict(claimId=component_id,
            text=component['text'], parentText=fixture['answer'])],
            evidence=[dict(evidenceId=e['evidenceId'], text=e['text'],
                quotes=prior.source_quote_spans(e['evidenceId'], e['text']))
                for e in fixture['evidence']])
        prefix = RUN / 'calls' / f'{index+1:02d}-context'
        prior.write(prefix.with_suffix('.payload.json'), payload)
        parsed = prior.call(prefix, plan, [dict(role='system', content=plan['contextRubric']),
            dict(role='user', content=json.dumps(payload, ensure_ascii=False))],
            plan['contextMaxCompletionTokens'], None)
        try:
            checked = prior.validate_isolated('context', payload, prior.resolve_quote_ids('context', payload, parsed))
        except ValueError as error:
            prior.write(prefix.with_suffix('.guard-failure.json'), dict(error=str(error), scoreEligible=False))
            raise
        by_id = {item['claimId']: item['supported'] for item in checked['claims']}
        component_results = [dict(claimId=component_id,
            expectedSupported=component['supported'], actualSupported=by_id[component_id])]
        verdict = by_id[component_id]
        prior.write(prefix.with_suffix('.reviewed.json'), dict(caseId=fixture['id'],
            componentId=component_id, expectedSupported=component['supported'], actualSupported=verdict,
            matchesFrozenExpectation=all(item['expectedSupported'] == item['actualSupported'] for item in component_results),
            components=component_results, verdict=checked))


def summarize():
    checked_plan()
    rows = [prior.read(path) for path in sorted((RUN / 'calls').glob('*.reviewed.json'))]
    requests = list((RUN / 'calls').glob('*.request.json'))
    results = [prior.read(path) for path in sorted((RUN / 'calls').glob('*.result.json'))]
    partition = prior.read(RUN / 'partition-reviewed.json') if (RUN / 'partition-reviewed.json').exists() else None
    result = dict(plannedSupportCases=8, plannedComponents=13, completedComponents=len(rows),
        actualExternalCalls=len(requests),
        mismatches=[r['componentId'] for r in rows if not r['matchesFrozenExpectation']],
        allTechnicalSpansRetained=partition and partition['allFrozenTechnicalSpansRetained'],
        knownTokenTotal=sum(r['usage']['total_tokens'] for r in results if r.get('usage')),
        unknownUsageRequests=len(requests) - sum(bool(r.get('usage')) for r in results),
        newGenerationCalls=0, formalQualityClaim=False, rows=rows)
    result['developerCalibrationPass'] = len(rows) == 13 and not result['mismatches'] \
        and result['allTechnicalSpansRetained'] and all(r['providerComplete'] for r in results)
    target = RUN / f'summary-{len(requests):02d}.json'
    prior.write(target, result)
    print(json.dumps({k:v for k,v in result.items() if k != 'rows'}))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('stage', choices=('prepare', 'partition', 'context', 'summarize'))
    parser.add_argument('--offset', type=int, default=0)
    parser.add_argument('--limit', type=int, default=2)
    args = parser.parse_args()
    if args.stage == 'prepare': prepare()
    elif args.stage == 'partition': partition()
    elif args.stage == 'context': context(args.offset, args.limit)
    else: summarize()
