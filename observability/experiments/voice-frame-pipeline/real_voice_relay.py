"""Loopback evidence relay: real product outputs, controlled subtitle input, no microphone."""
import argparse
import asyncio
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import time

from aiohttp import ClientSession, ClientTimeout, WSMsgType, web


class Relay:
    def __init__(self, args):
        self.args = args
        self.output = args.output.resolve()
        self.output.mkdir(parents=True, exist_ok=False)
        self.inputs = json.loads(args.inputs.read_text(encoding='utf-8'))['texts']
        self.sessions = {}
        self.audio_sequence = 0
        self.queue = asyncio.Queue(maxsize=1024)
        self.http = None
        self.writer = None

    def record(self, kind, **values):
        self.queue.put_nowait(dict(kind=kind, at=datetime.now(timezone.utc).isoformat(),
                                 monotonicSeconds=time.monotonic(), arm=self.args.arm, **values))

    async def write_records(self):
        with (self.output / 'events.jsonl').open('x', encoding='utf-8') as stream:
            while True:
                item = await self.queue.get()
                try:
                    if item is None:
                        return
                    audio = item.pop('_audio', None)
                    if audio is not None:
                        file = self.output / item['audioFile']
                        await asyncio.to_thread(file.write_bytes, audio)
                    stream.write(json.dumps(item, ensure_ascii=False) + '\n')
                    stream.flush()
                finally:
                    self.queue.task_done()

    async def metrics(self, label, session_id=None):
        try:
            async with self.http.get(self.args.target + '/actuator/prometheus') as response:
                text = await response.text()
                rows = [line for line in text.splitlines() if not line.startswith('#')
                        and ('token_usage' in line or line.startswith('app_voice_interview_'))]
                self.record('metrics_snapshot', label=label, sessionId=session_id,
                            httpStatus=response.status, lines=rows)
        except Exception as error:
            self.record('metrics_failed', label=label, errorType=type(error).__name__)

    async def start(self, app):
        self.http = ClientSession(timeout=ClientTimeout(total=65, connect=10))
        self.writer = asyncio.create_task(self.write_records())
        source = Path(__file__).read_bytes()
        (self.output / 'source.py').write_bytes(source)
        (self.output / 'plan.json').write_text(json.dumps(dict(
            arm=self.args.arm, target=self.args.target, port=self.args.port,
            sourceSha256=hashlib.sha256(source).hexdigest(),
            inputsSha256=hashlib.sha256(self.args.inputs.read_bytes()).hexdigest(), inputs=self.inputs,
            scope='real product LLM/TTS/output; injected controlled subtitles, no ASR/microphone timing',
            recordAudioAfterForward=True), ensure_ascii=False, indent=2), encoding='utf-8')
        await self.metrics('relay_start')

    async def stop(self, app):
        await self.metrics('relay_stop')
        await self.http.close()
        await self.queue.join()
        await self.queue.put(None)
        await self.writer

    async def api(self, request):
        body = await request.read()
        is_create = request.method == 'POST' and request.path == '/api/voice-interview/sessions'
        if is_create:
            payload = json.loads(body)
            if payload.get('resumeId') is not None or len(self.sessions) >= 10:
                return web.json_response(dict(code=400, message='Controlled study input/session limit', data=None))
        headers = {name:value for name,value in request.headers.items()
                   if name.lower() in ('content-type', 'accept')}
        async with self.http.request(request.method, self.args.target + request.rel_url.path_qs,
                                     data=body, headers=headers) as response:
            output = await response.read()
            if is_create:
                parsed = json.loads(output)
                value = parsed.get('data') or {}
                session_id = value.get('sessionId')
                if parsed.get('code') == 200 and session_id:
                    self.sessions[str(session_id)] = len(self.sessions) % len(self.inputs)
                self.record('session_create', request=payload, response=parsed, httpStatus=response.status)
            return web.Response(body=output, status=response.status,
                                headers={'Content-Type':response.headers.get('Content-Type','application/json')})

    def event_record(self, direction, session_id, message):
        event = json.loads(message)
        kept = {key:value for key,value in event.items() if key in (
            'type', 'action', 'turnId', 'sequence', 'eventId', 'eventType', 'turnPhase',
            'cancelRequestId', 'cancelOutcome',
            'text', 'content', 'isFinal', 'final', 'index', 'isLast', 'sentenceIndex', 'frameIndex',
            'endOfSentence', 'sampleRate', 'channels', 'bitsPerSample', 'encoding', 'format', 'message',
            'data')}
        if event.get('type') in ('audio', 'audio_chunk', 'audio_frame'):
            data = base64.b64decode(event.get('data') or '', validate=True)
            kept.pop('data', None)
            if data:
                self.audio_sequence += 1
                kept['audioFile'] = f'{self.audio_sequence:05d}.audio'
                kept['audioBytes'] = len(data)
                kept['audioSha256'] = hashlib.sha256(data).hexdigest()
                kept['_audio'] = data
        self.record(direction, sessionId=session_id, **kept)
        return event

    async def socket(self, request):
        session_id = request.match_info['session_id']
        if session_id not in self.sessions:
            raise web.HTTPForbidden(text='Only sessions created in this controlled relay are allowed')
        front = web.WebSocketResponse(max_msg_size=8 * 1024 * 1024)
        await front.prepare(request)
        target = self.args.target.replace('http:', 'ws:', 1) + request.path
        async with self.http.ws_connect(target, max_msg_size=8 * 1024 * 1024) as back:
            self.record('socket_open', sessionId=session_id)
            inject_after_ready = True

            async def to_product():
                nonlocal inject_after_ready
                async for message in front:
                    if message.type == WSMsgType.TEXT:
                        event = self.event_record('client_event', session_id, message.data)
                        if event.get('type') == 'audio':
                            raise ValueError('Microphone/audio submission is outside this preflight protocol')
                        if event.get('type') == 'control' and event.get('action') == 'submit':
                            expected = self.inputs[self.sessions[session_id]]
                            if event.get('data',{}).get('text') != expected:
                                raise ValueError('Submitted input differs from frozen controlled script')
                            await self.metrics('before_submit', session_id)
                        await back.send_str(message.data)
                        if event.get('type') == 'control' and event.get('action') == 'submit':
                            self.sessions[session_id] = (self.sessions[session_id] + 1) % len(self.inputs)
                        if event.get('type') == 'control' and event.get('action') == 'reconnect_asr':
                            inject_after_ready = True
                    elif message.type == WSMsgType.BINARY:
                        raise ValueError('Binary microphone input is outside scope')

            async def to_browser():
                nonlocal inject_after_ready
                async for message in back:
                    if message.type == WSMsgType.TEXT:
                        await front.send_str(message.data)
                        event = self.event_record('server_event', session_id, message.data)
                        if event.get('type') == 'control' and event.get('action') == 'asr_ready' and inject_after_ready:
                            inject_after_ready = False
                            subtitle = dict(type='subtitle', text=self.inputs[self.sessions[session_id]], isFinal=False)
                            await front.send_json(subtitle)
                            self.record('controlled_input_injected', sessionId=session_id, **subtitle)
                        if event.get('type') == 'control' and event.get('action') in ('audio_complete','turn_cancelled','turn_failed'):
                            await self.metrics('after_terminal', session_id)
                    elif message.type == WSMsgType.BINARY:
                        raise ValueError('Unexpected binary product WS event')

            tasks = [asyncio.create_task(to_product()), asyncio.create_task(to_browser())]
            try:
                done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for task in done:
                    task.result()
            except Exception as error:
                self.record('relay_failed', sessionId=session_id, errorType=type(error).__name__,
                            message=str(error) if isinstance(error,ValueError) else None)
                await front.close(code=1011, message=b'Study relay failed')
            finally:
                for task in tasks:
                    if not task.done():
                        task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                await back.close()
                await front.close()
                self.record('socket_closed', sessionId=session_id)
        return front


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--target', choices=['http://127.0.0.1:18080','http://127.0.0.1:18081'], required=True)
    parser.add_argument('--port', type=int, choices=[18187,18188], required=True)
    parser.add_argument('--arm', choices=['whole','frames'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--inputs', type=Path, required=True)
    args = parser.parse_args()
    relay = Relay(args)
    app = web.Application()
    app.router.add_route('*','/api/{tail:.*}',relay.api)
    app.router.add_get('/ws/voice-interview/{session_id}',relay.socket)
    app.on_startup.append(relay.start)
    app.on_cleanup.append(relay.stop)
    web.run_app(app,host='127.0.0.1',port=args.port)


if __name__ == '__main__':
    main()
