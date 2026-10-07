"""Capture and clean only the one controlled browser session created in this run."""
import json
from pathlib import Path
import requests
from experimental_index_snapshot import current, assert_scope, vectors_hash, query

OUT = Path(__file__).resolve().parent/'runs/rag-stream-terminal-20261005-r3/actual-browser'
BASE='http://127.0.0.1:18080'
QUESTION='请用两句简短回答：根据 XREADGROUP 公开文档，NOACK 是否把消息放进 PEL？文档是否给出我们生产任务重试的具体秒数？没有依据的部分写信息不足。'
def result(response):
    response.raise_for_status(); value=response.json()
    if value['code']!=200: raise ValueError(value['message'])
    return value['data']
def write(name,value): (OUT/name).write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')

rows=result(requests.get(BASE+'/api/rag-chat/sessions',timeout=10))
if len(rows)!=1 or rows[0]['title']!='redis-xreadgroup': raise ValueError('Unexpected session scope; do not delete')
sid=rows[0]['id']; detail=result(requests.get(BASE+f'/api/rag-chat/sessions/{sid}',timeout=10))
if len(detail['messages'])!=2 or detail['messages'][0]['content']!=QUESTION or [k['id'] for k in detail['knowledgeBases']]!=[3]:
    raise ValueError('Unexpected messages or knowledge-base scope; do not delete')
write('persisted-session.json',detail)
assistant=detail['messages'][1]
assert assistant['generationState']=='COMPLETED' and assistant['completed'] is True
assert assistant['content'] and len(assistant['evidence'])==8
dom=(OUT/'reloaded-dom.txt').read_text(encoding='utf-8')
assert 'NOACK' in dom and '信息不足' in dom and '检索来源' in dom
text=requests.get(BASE+'/actuator/prometheus',timeout=10).text
(OUT/'metrics-after.txt').write_text('\n'.join(l for l in text.splitlines() if l.startswith('gen_ai_client_token_usage_total{')),encoding='utf-8')
result(requests.delete(BASE+f'/api/rag-chat/sessions/{sid}',timeout=10))
state=current(); assert_scope(state)
write('result.json',dict(scope='one actual browser normal RAG turn + reload; no quality or paired latency claim',
    sessionId=sid,completed=True,sources=8,ownSessionDeleted=True,publicVectorSha256=vectors_hash(state),
    counts=query("SELECT json_build_object('sessions',(SELECT count(*) FROM rag_chat_sessions),'messages',(SELECT count(*) FROM rag_chat_messages),'docs',(SELECT count(*) FROM knowledge_bases),'vectors',(SELECT count(*) FROM vector_store))")))
print(json.dumps(dict(completed=True,reloadVerified=True,ownSessionDeleted=True)))
