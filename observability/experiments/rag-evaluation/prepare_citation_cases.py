"""Use already exposed dev facts only; exact frozen public spans, never user documents."""
import hashlib
import json
from pathlib import Path
import random

ROOT = Path(__file__).resolve().parents[3]
PACK = ROOT / 'data/local/fact-gold-v1-20261001-r2'
RUN = ROOT / 'observability/experiments/rag-evaluation/runs/citation-chain-20261005-r1'


def main():
    manifest = json.loads((PACK / 'manifest.json').read_text(encoding='utf-8'))
    docs = {d['documentId']: d for d in manifest['documents']}
    facts = {d['factId']: d for d in map(json.loads, (PACK / manifest['factsPath']).read_text(encoding='utf-8').splitlines())}
    gold = {d['id']: d for d in map(json.loads, (PACK / 'dev.jsonl').read_text(encoding='utf-8').splitlines())}
    selected = [gold[k] for k in ('primary-dev-pg-01', 'primary-dev-rd-04', 'primary-dev-sp-01',
                                 'primary-dev-cross-01')]
    partial = dict(gold['primary-dev-rd-04'], id='controlled-partial-rd',
        question=gold['primary-dev-rd-04']['question'] + ' 同时说明我们的生产重试间隔具体是多少秒，资料没提供的部分明确说明。',
        referenceAnswer=gold['primary-dev-rd-04']['referenceAnswer'] + ' 资料未提供生产重试间隔，不能确定秒数。',
        missingRequirement='Production retry seconds are not in supplied public documentation.')
    selected.append(partial)
    selected.append(gold['primary-dev-rd-07'])
    cases = []
    for item in selected:
        ids = item['referenceFactIds'] or ['rd-noack-normal', 'rd-noack-claim-exception']
        contexts = []
        for fact_id in ids:
            fact = facts[fact_id]
            doc = docs[fact['documentId']]
            raw = (PACK / doc['canonicalPath']).read_bytes()
            if hashlib.sha256(raw).hexdigest() != doc['sourceTextSha256']:
                raise ValueError('Public source drift')
            text = raw.decode('utf-8')
            if text[fact['start']:fact['end']] != fact['exactQuote']:
                raise ValueError('Fact no longer matches exact source span')
            contexts.append(dict(documentId=doc['documentId'], sourceUrl=doc['sourceUrl'],
                sourceVersion=doc['sourceVersion'], body=fact['exactQuote'], factId=fact_id,
                sourceTextSha256=doc['sourceTextSha256'], start=fact['start'], end=fact['end']))
        cases.append(dict(item, contexts=contexts))
    rng = random.Random(20261005)
    order = list(range(6)); rng.shuffle(order)
    directions = [0, 1, 0, 1, 0, 1]; rng.shuffle(directions)
    schedule = []
    for index, direction in zip(order, directions):
        for arm in (['plain', 'numbered'] if direction == 0 else ['numbered', 'plain']):
            schedule.append(dict(caseId=cases[index]['id'], arm=arm))
    plan = dict(payloadOrigin='frozen-public-primary-dev-v1', scope='six-case fixed public context generation diagnostic; not fresh formal or retrieval evaluation',
        humanReviewed=False, review='source author Agent, not blind or human reviewed', seed=20261005,
        cases=cases, schedule=schedule, submittedCalls=12, maximumCompletionTokensPerCall=480,
        model='qwen3.8-flash', temperature=0.2, thinking=False,
        factorsChanged=['context numbering', 'citation instruction'], bodiesAndOrderingUnchanged=True)
    (RUN / 'inputs.json').write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(cases=len(cases), calls=len(schedule), armsBalanced=True)))


if __name__ == '__main__':
    main()
