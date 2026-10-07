"""Bounded public-only retrieval audit; no Chat calls, no full-body API changes."""
import hashlib
import json
from pathlib import Path
import requests

from experimental_index_snapshot import current, assert_scope, vectors_hash, query
from ingest_primary_dev import provider_snapshot, utc_now

HERE = Path(__file__).resolve().parent
RUN = HERE / 'runs/term-actual-context-20261005-r1'
BASE = 'http://127.0.0.1:18080'


def write(name, value):
    (RUN / name).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')


def meters():
    response = requests.get(BASE + '/actuator/prometheus',timeout=10)
    response.raise_for_status()
    return '\n'.join(x for x in response.text.splitlines() if x.startswith('gen_ai_client_token_usage_total{')) + '\n'


def state():
    value = current()
    assert_scope(value)
    counts = query("SELECT json_build_object('documents',(SELECT count(*) FROM knowledge_bases),'vectors',(SELECT count(*) FROM vector_store),'ragSessions',(SELECT count(*) FROM rag_chat_sessions),'ragMessages',(SELECT count(*) FROM rag_chat_messages),'voiceSessions',(SELECT count(*) FROM voice_interview_sessions));")
    if counts != dict(documents=15,vectors=123,ragSessions=0,ragMessages=0,voiceSessions=0):
        raise ValueError('Unexpected isolated data scope; stop before calls')
    return value, dict(publicVectorSha256=vectors_hash(value), counts=counts, capturedAt=utc_now())


def main():
    if RUN.exists():
        raise ValueError('Preserve previous run')
    before, scope = state()
    if requests.get(BASE + '/actuator/health',timeout=8).json()['status'] != 'UP':
        raise ValueError('Isolated application is unhealthy')
    RUN.mkdir()
    write('scope-before.json',scope)
    write('provider-before.json',provider_snapshot())
    (RUN / 'metrics-before.txt').write_text(meters(),encoding='utf-8')
    source = HERE / 'runs/term-generation-dev-20261005-r2/inputs.json'
    plan = json.loads(source.read_text(encoding='utf-8'))
    write('plan.json',dict(publicKnowledgeBaseIds=[1,2,3,4],maxRetrievalRequests=2,
        chatCalls=0,rewrite=False,retrievalMode='HYBRID',contextTokenBudget=6000,
        questionOriginSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        publicFingerprint=scope['publicVectorSha256'],
        note='Selected API evidence IDs mapped to scoped public DB bodies. No generation, no wire-level prompt capture, no production performance claim.'))
    rows = {x['id']:x for x in before['vectors']}
    outcomes = []
    for index, case_id in enumerate(('primary-dev-rd-04','controlled-partial-rd')):
        case = next(x for x in plan['cases'] if x['id']==case_id)
        payload = dict(queries=[dict(question=case['question'],knowledgeBaseIds=[1,2,3,4])],
            rewrite=False,retrievalMode='HYBRID',contextTokenBudget=6000)
        write(f'{index:02d}-request.json',payload)
        response = requests.post(BASE + '/api/knowledgebase/evaluation/retrieval',json=payload,timeout=(10,90))
        raw = response.json()
        write(f'{index:02d}-response.json',dict(httpStatus=response.status_code,result=raw))
        response.raise_for_status()
        if raw.get('code') != 200 or len(raw['data']['items']) != 1:
            raise ValueError('Failed retrieval; no implicit retry')
        result = raw['data']['items'][0]
        bodies = []
        for evidence in result['evidence']:
            row = rows[evidence['vectorDocumentId']]
            if int(row['metadata']['kb_id']) not in (1,2,3,4):
                raise ValueError('Retrieved source outside frozen public scope')
            bodies.append(dict(id=row['id'],content=row['content'],metadata=row['metadata'],
                bodySha256=hashlib.sha256(row['content'].encode('utf-8')).hexdigest()))
        write(f'{index:02d}-selected-public-bodies.json',bodies)
        joined = '\n\n---\n\n'.join(x['content'] for x in bodies)
        outcomes.append(dict(caseId=case_id,selectedIds=[x['id'] for x in bodies],
            contextTokens=result.get('contextTokenEstimate'),vectorSearchCalls=result['vectorSearchCalls'],
            containsPelFullDefinition='Pending Entries List' in joined or 'pending entries list' in joined,
            containsNoackReliabilityLossCondition='reliability is not a requirement' in joined and 'occasional message loss' in joined,
            containsClaimException='does not apply to retrieved pending entries' in joined,
            inference='Body reconstructed from API-selected UUIDs and scoped frozen DB content; no actual Chat generation in this run.'))
        (RUN / f'{index:02d}-metrics-after.txt').write_text(meters(),encoding='utf-8')
    after, end = state()
    write('scope-after.json',end)
    if vectors_hash(after) != vectors_hash(before):
        raise ValueError('Public vectors changed')
    write('provider-after.json',provider_snapshot())
    write('summary.json',dict(actualRetrievalRequests=2,actualChatCalls=0,results=outcomes,
        unchangedPublicFingerprint=True,noSessionWrites=True,formalQualityClaim=False))
    print(json.dumps(outcomes,ensure_ascii=False))


if __name__ == '__main__':
    main()
