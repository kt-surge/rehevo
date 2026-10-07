"""Two actual public RAG session probes (normal and stop); metrics contain actual available usage."""
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import time
import requests

from experimental_index_snapshot import current, assert_scope, vectors_hash, query

BASE = 'http://127.0.0.1:18080'
RUN = Path(sys.argv[1])
OUT = RUN / 'actual-http'

def write(name, value):
    (OUT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

def result(response):
    response.raise_for_status()
    value = response.json()
    if value['code'] != 200:
        raise ValueError(value['message'])
    return value['data']

def meters():
    value = requests.get(BASE + '/actuator/prometheus', timeout=10)
    value.raise_for_status()
    return '\n'.join(line for line in value.text.splitlines() if line.startswith('gen_ai_client_token_usage_total{')) + '\n'

def main():
    OUT.mkdir(exist_ok=False)
    before = current(); assert_scope(before)
    write('scope-before.json', {'publicVectorSha256': vectors_hash(before),
        'counts': query("SELECT json_build_object('sessions',(SELECT count(*) FROM rag_chat_sessions),'messages',(SELECT count(*) FROM rag_chat_messages),'docs',(SELECT count(*) FROM knowledge_bases),'vectors',(SELECT count(*) FROM vector_store))")})
    if requests.get(BASE + '/actuator/health', timeout=10).json()['status'] != 'UP':
        raise ValueError('Isolated application is not healthy')
    (OUT / 'metrics-before.txt').write_text(meters(), encoding='utf-8')
    cases = [('normal', '请用两句简短回答：根据 XREADGROUP 公开文档，NOACK 是否把消息放进 PEL？文档是否给出我们生产任务重试的具体秒数？没有依据的部分写信息不足。', False),
        ('cancel', '只根据 XREADGROUP 公开文档，分十个简短要点说明消费组、消息投递、PEL 与 NOACK；对未说明的生产环境参数不要猜测。', True)]
    sessions = []; outcomes = []
    try:
        for name, question, stop in cases:
            session = result(requests.post(BASE + '/api/rag-chat/sessions',
                json={'knowledgeBaseIds':[3], 'title':f'controlled-terminal-{name}-20261005'}, timeout=10))
            session_id = session['id']; sessions.append(session_id)
            write(f'{name}-request.json', {'sessionId':session_id,'knowledgeBaseIds':[3],'question':question,'stopAfterFirstDelta':stop})
            events = []; event_name = ''; data = []; message_id = None; stop_result = None
            started = time.perf_counter()
            with (OUT / f'{name}-raw.jsonl').open('x', encoding='utf-8') as raw:
                with requests.post(BASE + f'/api/rag-chat/sessions/{session_id}/messages/stream',
                    json={'question':question}, stream=True, timeout=(10,210)) as response:
                    response.raise_for_status()
                    for line in response.iter_lines(chunk_size=1):
                        text = line.decode('utf-8')
                        elapsed = (time.perf_counter()-started)*1000
                        raw.write(json.dumps({'at':datetime.now(timezone.utc).isoformat(),'elapsedMs':elapsed,'line':text},ensure_ascii=False)+'\n'); raw.flush()
                        if text.startswith('event:'): event_name = text[6:].strip()
                        elif text.startswith('data:'): data.append(text[5:].removeprefix(' '))
                        elif not text and data:
                            event = json.loads('\n'.join(data)); data=[]
                            if event['event'] != event_name: raise ValueError('SSE name/data mismatch')
                            events.append({'elapsedMs':elapsed, **event})
                            if event_name == 'start': message_id=event['messageId']
                            if stop and stop_result is None and event_name == 'delta' and event.get('content','').strip():
                                stop_result=result(requests.post(BASE + f'/api/rag-chat/sessions/{session_id}/messages/{message_id}/cancel', timeout=15))
            detail = result(requests.get(BASE+f'/api/rag-chat/sessions/{session_id}',timeout=10))
            write(f'{name}-detail.json',detail)
            content=''.join(event.get('content') or '' for event in events if event['event']=='delta')
            terminal=[event for event in events if event['event']=='terminal']
            persisted=next(message for message in detail['messages'] if message['id']==message_id)
            outcome=dict(case=name,events=events,stopResponse=stop_result,terminalCount=len(terminal),
                persistedState=persisted['generationState'],completed=persisted['completed'],
                exactStreamPersistedContent=content==persisted['content'],prefixCharacters=len(content),
                note='Actual single-instance HTTP correctness probe; not browser, latency comparison or quality score.')
            write(f'{name}-result.json',outcome); outcomes.append(outcome)
            assert len(terminal)==1 and terminal[0]['generationState']==persisted['generationState']
            assert outcome['exactStreamPersistedContent']
            assert persisted['completed']==(persisted['generationState']=='COMPLETED')
            if stop: assert stop_result['generationState']=='CANCELLED' and persisted['generationState']=='CANCELLED'
            else: assert persisted['generationState']=='COMPLETED' and content.strip()
    finally:
        (OUT / 'metrics-after.txt').write_text(meters(),encoding='utf-8')
        cleanup=[]
        for session_id in sessions:
            try:
                result(requests.delete(BASE+f'/api/rag-chat/sessions/{session_id}',timeout=15)); cleanup.append({'sessionId':session_id,'deleted':True})
            except Exception as error: cleanup.append({'sessionId':session_id,'deleted':False,'error':str(error)})
        write('cleanup.json',cleanup)
        after=current(); assert_scope(after)
        write('scope-after.json',{'publicVectorSha256':vectors_hash(after),'unchanged':vectors_hash(after)==vectors_hash(before),
            'counts':query("SELECT json_build_object('sessions',(SELECT count(*) FROM rag_chat_sessions),'messages',(SELECT count(*) FROM rag_chat_messages),'docs',(SELECT count(*) FROM knowledge_bases),'vectors',(SELECT count(*) FROM vector_store))")})
    write('summary.json',{'cases':len(outcomes),'passed':len(outcomes),'modelGenerationRequests':2,
        'cancelledCallUsageMayBeUnknown':True,'scope':'public existing document 3 only, own sessions cleaned',
        'latencyBenefitClaim':False,'qualityScoreClaim':False})
    print(json.dumps({'cases':len(outcomes),'normal':'COMPLETED','cancel':'CANCELLED','sessionsCleaned':True}))

if __name__=='__main__': main()
