"""Loopback protocol fault fixture for the actual React voice page.

No model, credentials, microphone or production database. Timing observations
are controlled browser evidence, never provider or production performance.
"""
import argparse
import asyncio
import base64
import hashlib
import io
import json
import math
from pathlib import Path
import struct
import time
import wave
from aiohttp import web, WSMsgType


def pcm(seconds):
    return struct.pack('<' + 'h' * int(24000 * seconds),
                       *(int(600 * math.sin(2 * math.pi * 440 * i / 24000)) for i in range(int(24000 * seconds))))


def wav(value):
    stream = io.BytesIO()
    with wave.open(stream, 'wb') as output:
        output.setnchannels(1); output.setsampwidth(2); output.setframerate(24000); output.writeframes(value)
    return base64.b64encode(stream.getvalue()).decode('ascii')


def create_app(output, port, start_counter=0, normal_only=False):
    if output.exists():
        raise ValueError('Preserve existing run; choose a new output')
    output.parent.mkdir(parents=True, exist_ok=True)
    events, counter = [], start_counter
    tones = {duration: pcm(duration) for duration in (0.3, 0.5, 2.0)}

    def record(kind, **values):
        event = dict(kind=kind, evidenceType='controlled_browser_audio_schedule',
                     monotonicSeconds=time.monotonic(), **values)
        events.append(event)
        with output.open('a', encoding='utf-8') as file:
            file.write(json.dumps(event, ensure_ascii=False) + '\n')

    def result(data):
        return web.json_response(dict(code=200, message='success', data=data))

    def session():
        return dict(sessionId=910, roleType='受控语音验收', currentPhase='TECH', status='IN_PROGRESS',
                    startTime=time.strftime('%Y-%m-%dT%H:%M:%S'), plannedDuration=30,
                    webSocketUrl=f'ws://127.0.0.1:{port}/ws/voice-interview/910')

    async def http(request):
        if request.path == '/diagnostics':
            return web.json_response(dict(events=events, evidenceType='controlled_browser_audio_schedule'))
        record('http', method=request.method, path=request.path)
        if request.path == '/api/interview/skills':
            return result([dict(id='java-backend', name='Java 后端开发', description='受控验收',
                                categories=[], isPreset=True, sourceJd=None)])
        if request.path == '/api/voice-interview/sessions' and request.method == 'GET':
            return result([dict(sessionId=910, roleType='受控语音验收', status='PAUSED', currentPhase='TECH',
                                createdAt=session()['startTime'], updatedAt=session()['startTime'],
                                actualDuration=0, messageCount=0, evaluateStatus=None)])
        if request.path.endswith('/messages'):
            return result([])
        if request.path.endswith('/pause') or request.path.endswith('/end'):
            return result(None)
        if request.path.endswith('/resume') or request.path.endswith('/910') or (
                request.path == '/api/voice-interview/sessions' and request.method == 'POST'):
            return result(session())
        return result([])

    async def websocket(request):
        nonlocal counter
        ws = web.WebSocketResponse(heartbeat=20)
        await ws.prepare(request)
        tasks, sequence, current_turn = set(), 0, None
        cancelled = set()

        async def send(payload, turn=None):
            nonlocal sequence
            sequence += 1
            if ws.closed:
                return
            await ws.send_json(dict(turnId=turn, sequence=sequence, eventId=f'controlled-{sequence}',
                                    createdAt=int(time.time() * 1000), **payload))
            record('server_event', turnId=turn, sequence=sequence, type=payload['type'],
                   action=payload.get('action'), frameIndex=payload.get('frameIndex'),
                   sentenceIndex=payload.get('sentenceIndex'), endOfSentence=payload.get('endOfSentence'),
                   bytes=len(base64.b64decode(payload.get('data', ''))))

        async def prepare_answer():
            await send(dict(type='subtitle', text='我负责检索与语音模块，使用对照实验验证改进。', isFinal=False))

        async def frame(turn, sentence, index, duration=0.3, end=False, **override):
            value = dict(type='audio_frame', data='' if end else base64.b64encode(tones[duration]).decode('ascii'),
                         sentenceIndex=sentence, frameIndex=index, endOfSentence=end, encoding='pcm_s16le',
                         sampleRate=24000, channels=1, bitsPerSample=16)
            value.update(override)
            await send(value, turn)

        async def respond(turn, mode):
            await asyncio.sleep(0.15)
            await send(dict(type='text', content='请说明你怎样验证优化后的结果。', final=True), turn)
            if mode == 'wav':
                for index in range(2):
                    await send(dict(type='audio_chunk', data=wav(tones[0.3]), index=index, isLast=index == 1), turn)
            elif mode == 'cancel-playing' or mode == 'disconnect':
                for index in range(16):
                    await frame(turn, 0, index, 0.5)
                await frame(turn, 0, 16, end=True)
                if mode == 'disconnect':
                    await asyncio.sleep(0.4)
                    await ws.close(code=1011, message=b'controlled disconnection')
                    return
            elif mode == 'partial-failure':
                await frame(turn, 0, 0, 2.0)
                await asyncio.sleep(0.1)
                await send(dict(type='control', action='turn_failed', turnPhase='FAILED', message='受控部分音频失败'), turn)
                await asyncio.sleep(0.15)
                await frame(turn, 0, 1)
                await send(dict(type='text', content='迟到失败文本不应展示', final=True), turn)
                await prepare_answer()
                return
            elif mode == 'wrong-format':
                await frame(turn, 0, 0, channels=2)
                await prepare_answer()
                return
            elif mode == 'missing-frame':
                await frame(turn, 0, 0)
                await frame(turn, 0, 2)
                await prepare_answer()
                return
            elif mode == 'late-cancel-confirmation':
                for index in range(8):
                    await frame(turn, 0, index, 0.5)
                await frame(turn, 0, 8, end=True)
                await asyncio.sleep(0.4)
                await send(dict(type='control', action='turn_cancelled', turnPhase='CANCELLED',
                                cancelRequestId='old-cancel-0001'), 'controlled-turn-9')
                await send(dict(type='control', action='cancel_confirmed',
                                cancelRequestId='old-cancel-0001', cancelOutcome='already_terminal'))
            else:
                for sentence in range(2):
                    for index in range(2):
                        await frame(turn, sentence, index)
                    await frame(turn, sentence, 2, end=True)
            await send(dict(type='control', action='audio_complete'), turn)
            await send(dict(type='control', action='turn_completed', turnPhase='COMPLETED'), turn)
            await asyncio.sleep(1)
            await prepare_answer()

        def launch(coroutine):
            task = asyncio.create_task(coroutine); tasks.add(task); task.add_done_callback(tasks.discard)

        await send(dict(type='control', action='asr_ready'))
        await prepare_answer()
        try:
            async for message in ws:
                if message.type != WSMsgType.TEXT:
                    continue
                value = json.loads(message.data)
                if value.get('type') != 'control':
                    continue
                data = value.get('data') or {}
                action = value.get('action')
                record('client_control', action=action, turnId=current_turn,
                       data={key: item for key, item in data.items() if key not in ('text', 'data')})
                if action == 'submit':
                    counter += 1
                    mode = 'wav' if normal_only else {
                        1:'wav', 2:'frames', 3:'cancel-playing', 4:'partial-failure',
                        5:'wrong-format', 6:'missing-frame', 7:'disconnect', 9:'cancel-playing',
                        10:'late-cancel-confirmation'
                    }.get(counter, 'frames')
                    current_turn = f'controlled-turn-{counter}'
                    record('turn_scenario', mode=mode, turnId=current_turn)
                    await send(dict(type='control', action='turn_started', turnPhase='THINKING',
                                    clientRequestId=data.get('clientRequestId')), current_turn)
                    launch(respond(current_turn, mode))
                elif action == 'cancel':
                    turn = current_turn; cancelled.add(turn)
                    await send(dict(type='control', action='turn_cancelled', turnPhase='CANCELLED',
                                    cancelRequestId=data.get('cancelRequestId')), turn)
                    await asyncio.sleep(0.05)
                    await frame(turn, 0, 0)
                    await send(dict(type='text', content='迟到取消文本不应展示', final=True), turn)
                    await prepare_answer()
        finally:
            for task in list(tasks):
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            record('websocket_closed')
        return ws

    app = web.Application()
    app.router.add_get('/ws/voice-interview/910', websocket)
    app.router.add_route('*', '/{tail:.*}', http)
    record('fixture_started', port=port, startCounter=start_counter, normalOnly=normal_only,
           tones={str(seconds):dict(bytes=len(value), sha256=hashlib.sha256(value).hexdigest())
                                              for seconds, value in tones.items()})
    return app


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=18187)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--start-counter', type=int, default=0)
    parser.add_argument('--normal-only', action='store_true')
    args = parser.parse_args()
    web.run_app(create_app(args.output, args.port, args.start_counter, args.normal_only), host='127.0.0.1', port=args.port)
