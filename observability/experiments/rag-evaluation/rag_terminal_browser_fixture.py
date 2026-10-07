"""Controlled HTTP fixture for actual React page; no database, credentials, model or private materials."""
import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import time
from urllib.parse import urlparse

sessions = {}; lock = threading.RLock(); counter = 5000; output = None
stamp = '2026-10-05T07:00:00Z'
kb = dict(id=901,name='受控公开资料',category='浏览器故障验收',originalFilename='public-fixture.md',
    fileSize=1024,contentType='text/markdown',uploadedAt=stamp,lastAccessedAt=stamp,accessCount=0,
    questionCount=0,vectorStatus='COMPLETED',vectorError=None,chunkCount=1)

def log(event, **fields):
    with lock:
        with (output/'events.jsonl').open('a',encoding='utf-8') as handle:
            handle.write(json.dumps(dict(at=datetime.now(timezone.utc).isoformat(),event=event,**fields),ensure_ascii=False)+'\n')

class Handler(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.0'
    def log_message(self,*args): pass
    def send(self,data,code=200):
        raw=json.dumps(dict(code=code,message='success' if code==200 else '受控错误',data=data),ensure_ascii=False).encode()
        self.send_response(200); self.send_header('Content-Type','application/json'); self.send_header('Content-Length',str(len(raw))); self.end_headers(); self.wfile.write(raw)
    def do_GET(self):
        path=urlparse(self.path).path
        if path.startswith('/api/knowledgebase'): return self.send([kb] if 'stats' not in path else dict(totalCount=1,totalQuestionCount=0,totalAccessCount=0,completedCount=1,processingCount=0))
        if path=='/api/rag-chat/sessions':
            with lock: rows=[dict(id=s['id'],title=s['title'],messageCount=len(s['messages']),knowledgeBaseNames=[kb['name']],updatedAt=stamp,isPinned=False) for s in sessions.values()]
            return self.send(rows)
        if path.startswith('/api/rag-chat/sessions/'):
            sid=int(path.split('/')[4])
            with lock: s=sessions[sid]; detail=dict(id=sid,title=s['title'],knowledgeBases=[kb],messages=s['messages'],createdAt=stamp,updatedAt=stamp)
            return self.send(detail)
        return self.send([],404)
    def do_POST(self):
        global counter
        path=urlparse(self.path).path
        body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))) or '{}')
        if path=='/api/rag-chat/sessions':
            with lock:
                counter+=1; sid=counter; sessions[sid]=dict(id=sid,title=f'受控会话 {sid}',messages=[],cancel=threading.Event())
            log('create',sessionId=sid)
            return self.send(dict(id=sid,title=sessions[sid]['title'],knowledgeBaseIds=[901],createdAt=stamp))
        sid=int(path.split('/')[4]); s=sessions[sid]
        if path.endswith('/cancel'):
            mid=int(path.split('/')[6]); s['cancel'].set(); log('stop',sessionId=sid,messageId=mid)
            # Persist before confirming, as production protocol requires.
            with lock:
                assistant=next(m for m in s['messages'] if m['id']==mid)
                if assistant['generationState']=='GENERATING': assistant.update(generationState='CANCELLED',completed=False,generationErrorCode='USER_CANCELLED')
                state=assistant['generationState']
            return self.send(dict(event='terminal',messageId=mid,generationState=state,errorCode='USER_CANCELLED',message='已停止，回答未完成'))
        question=body['question']; mode='failed' if '失败' in question else 'disconnect' if '中断' in question else 'slow' if ('停止' in question or '慢速' in question) else 'normal'
        with lock:
            mid=sid*100+len(s['messages'])+2
            s['cancel'].clear()
            s['messages'].append(dict(id=mid-1,type='user',content=question,createdAt=stamp,evidence=[],completed=True,generationState=None))
            assistant=dict(id=mid,type='assistant',content='',createdAt=stamp,evidence=[],completed=False,generationState='GENERATING',generationErrorCode=None)
            s['messages'].append(assistant)
        self.send_response(200); self.send_header('Content-Type','text/event-stream'); self.end_headers()
        def emit(event,**fields):
            value=dict(event=event,messageId=mid,**fields)
            self.wfile.write(('event: '+event+'\ndata: '+json.dumps(value,ensure_ascii=False)+'\n\n').encode()); self.wfile.flush()
            log('sse',sessionId=sid,sseEvent=event,**{key:item for key,item in value.items() if key!='event'})
        try:
            emit('start',generationState='GENERATING')
            chunks=['受控前缀：中文😀，字面 `\\n` 保持。\n', '第二段事实。']
            for chunk in chunks:
                if s['cancel'].is_set(): break
                with lock: assistant['content']+=chunk
                emit('delta',content=chunk)
                if mode in ('failed','disconnect'): break
                if mode=='slow': s['cancel'].wait(30)
            if mode=='disconnect':
                assistant.update(generationState='CANCELLED',completed=False,generationErrorCode='CONNECTION_LOST')
                log('deliberate-eof',sessionId=sid,messageId=mid); return
            state='CANCELLED' if s['cancel'].is_set() else 'FAILED' if mode=='failed' else 'COMPLETED'
            with lock: assistant.update(generationState=state,completed=state=='COMPLETED',generationErrorCode='GENERATION_FAILED' if state=='FAILED' else 'USER_CANCELLED' if state=='CANCELLED' else None)
            emit('terminal',generationState=state,errorCode=assistant['generationErrorCode'],message='回答生成失败，已保留收到的内容' if state=='FAILED' else '已停止，回答未完成' if state=='CANCELLED' else None)
        except (BrokenPipeError,ConnectionResetError) as error:
            s['cancel'].set(); assistant.update(generationState='CANCELLED',completed=False)
            log('client-disconnect',sessionId=sid,messageId=mid,error=type(error).__name__)
    def do_DELETE(self):
        sid=int(urlparse(self.path).path.split('/')[4])
        with lock: sessions.pop(sid,None)
        log('delete',sessionId=sid); self.send(None)

if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('output',type=Path); parser.add_argument('--port',type=int,default=18083); args=parser.parse_args()
    output=args.output; output.mkdir(exist_ok=False)
    log('ready',fixtureOnly=True,modelCalls=0,port=args.port)
    ThreadingHTTPServer(('127.0.0.1',args.port),Handler).serve_forever()
