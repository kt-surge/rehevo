"""Actual REST/WS quality preflight; both arms share model, Skill, whole TTS, handler.

This is not a browser/hardware latency or formal P95 experiment.
"""
import argparse
import asyncio
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import random
import time

from aiohttp import ClientSession, ClientTimeout, WSMsgType


class Probe:
    def __init__(self, output, inputs, candidate_only=False):
        self.output = output.resolve()
        runs = Path(__file__).resolve().parent / 'runs'
        if not self.output.is_relative_to(runs.resolve()):
            raise ValueError('Output must be in experiment runs')
        for ancestor in [self.output, *self.output.parents]:
            if (ancestor / 'artifacts.sha256.json').exists():
                raise ValueError('Sealed output is read-only')
        self.output.mkdir(exist_ok=False, parents=True)
        self.plan = json.loads(inputs.read_text(encoding='utf-8'))
        self.source = Path(__file__).read_bytes()
        (self.output / 'source.py').write_bytes(self.source)
        (self.output / 'inputs.json').write_bytes(inputs.read_bytes())
        self.targets = {'baseline': 'http://127.0.0.1:18081', 'candidate': 'http://127.0.0.1:18080'}
        if candidate_only:
            self.targets = {'candidate': self.targets['candidate']}
        self.calls = sum(len(s['texts']) for s in self.plan['sequences']) * len(self.targets)
        if len(self.plan['sequences']) > 8 or self.calls > 32:
            raise ValueError('Small controlled batch required')
        self.owned = []
        self.audio_number = 0
        self.rows = []

    def record(self, kind, **values):
        row = dict(kind=kind, at=datetime.now(timezone.utc).isoformat(), **values)
        self.rows.append(row)
        with (self.output / 'events.jsonl').open('a', encoding='utf-8') as file:
            file.write(json.dumps(row, ensure_ascii=False) + '\n')

    async def metrics(self, arm, label):
        async with self.http.get(self.targets[arm] + '/actuator/prometheus') as response:
            text = await response.text()
            self.record('metrics', arm=arm, label=label, status=response.status,
                        lines=[line for line in text.splitlines() if not line.startswith('#')
                               and ('token_usage' in line or line.startswith('app_voice_interview_'))])

    async def receive(self, ws, arm, session, label, started):
        terminal = None
        final_text = None
        first_audio = None
        deadline = asyncio.get_running_loop().time() + 65
        while asyncio.get_running_loop().time() < deadline:
            remaining = deadline - asyncio.get_running_loop().time()
            msg = await ws.receive(timeout=remaining)
            if msg.type != WSMsgType.TEXT:
                raise ValueError(f'Unexpected WebSocket terminal/type: {msg.type.name}')
            event = json.loads(msg.data)
            if event.get('type') in ('audio', 'audio_chunk', 'audio_frame'):
                raw = base64.b64decode(event.pop('data', ''), validate=True)
                self.audio_number += 1
                name = f'{self.audio_number:04d}.audio'
                (self.output / name).write_bytes(raw)
                event.update(audioFile=name, audioBytes=len(raw), audioSha256=hashlib.sha256(raw).hexdigest())
                if raw and first_audio is None:
                    first_audio = (time.monotonic() - started) * 1000
            if event.get('type') == 'text' and (event.get('final') or event.get('isFinal')):
                final_text = event.get('text') or event.get('content')
            self.record('server_event', arm=arm, sessionId=session, label=label, event=event)
            # 现有开场协议只发送整段 audio，不发送普通回答的 turn_completed。
            if label.endswith('-opening') and event.get('type') == 'audio' and event.get('audioBytes', 0) > 0:
                terminal = 'opening_audio'
                break
            if event.get('type') == 'control' and event.get('action') in ('turn_completed', 'turn_failed', 'turn_cancelled'):
                terminal = event['action']
                break
        self.record('terminal', arm=arm, sessionId=session, label=label, terminal=terminal,
                    text=final_text, characters=len(final_text) if final_text else 0,
                    firstServerAudioMs=first_audio, totalServerMs=(time.monotonic()-started)*1000)
        if terminal not in ('turn_completed', 'opening_audio'):
            raise ValueError(f'{label}: server terminal {terminal}')

    async def create(self, arm, sequence):
        payload = dict(skillId='java-backend', difficulty='mid', llmProvider='dashscope',
                       introEnabled=False, techEnabled=True, projectEnabled=False, hrEnabled=False,
                       plannedDuration=10)
        async with self.http.post(self.targets[arm] + '/api/voice-interview/sessions', json=payload) as response:
            result = await response.json()
        if result.get('code') != 200 or not result.get('data', {}).get('sessionId'):
            raise ValueError('Controlled session creation failed')
        session = result['data']['sessionId']
        self.owned.append((arm, session))
        self.record('created', arm=arm, sequence=sequence, sessionId=session, payload=payload, response=result)
        ws = await self.http.ws_connect(self.targets[arm].replace('http:', 'ws:') + f'/ws/voice-interview/{session}')
        await self.receive(ws, arm, session, sequence + '-opening', time.monotonic())
        return session, ws

    async def run(self):
        rng = random.Random(self.plan['seed'])
        order = []
        for sequence in self.plan['sequences']:
            for index in range(len(sequence['texts'])):
                arms = list(self.targets)
                if rng.randrange(2): arms.reverse()
                order.append(dict(sequence=sequence['id'], index=index, arms=arms))
        (self.output / 'plan.json').write_text(json.dumps(dict(
            seed=self.plan['seed'], plannedCalls=self.calls,
            pairOrder=order, sourceSha256=hashlib.sha256(self.source).hexdigest(),
            scope='actual product REST/WS; controlled text, no ASR input/microphone/browser playback',
            limits='bounded owned sessions; no collector retries; all failures retained; product fallback retained'),
            ensure_ascii=False, indent=2), encoding='utf-8')
        async with ClientSession(timeout=ClientTimeout(total=70, connect=10)) as self.http:
            sockets = []
            try:
                for sequence in self.plan['sequences']:
                    pair = {}
                    for arm in self.targets:
                        session, ws = await self.create(arm, sequence['id'])
                        pair[arm] = (session, ws)
                        sockets.append(ws)
                    for index, text in enumerate(sequence['texts']):
                        step = next(row for row in order if row['sequence']==sequence['id'] and row['index']==index)
                        for arm in step['arms']:
                            session, ws = pair[arm]
                            label = sequence['id'] + '-' + str(index+1)
                            await self.metrics(arm, label + '-before')
                            event = dict(type='control', action='submit', data=dict(text=text,
                                clientRequestId=f'quality-{sequence["id"]}-{index+1}'))
                            self.record('submit', arm=arm, sessionId=session, label=label, event=event)
                            started = time.monotonic()
                            await ws.send_json(event)
                            await self.receive(ws, arm, session, label, started)
                            await self.metrics(arm, label + '-after')
                    for session, ws in pair.values(): await ws.close()
            except Exception as error:
                self.record('probe_failed', errorType=type(error).__name__, message=str(error))
                raise
            finally:
                for ws in sockets: await ws.close()
                for arm, session in self.owned:
                    async with self.http.delete(self.targets[arm] + f'/api/voice-interview/sessions/{session}') as response:
                        result = await response.json()
                    self.record('cleanup', arm=arm, sessionId=session, response=result)
                for arm in self.targets: await self.metrics(arm, 'probe_end')
        print(json.dumps(dict(status='finished', answerCalls=self.calls, ownedSessionsCleaned=len(self.owned))))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--inputs', type=Path, required=True)
    parser.add_argument('--candidate-only', action='store_true')
    args = parser.parse_args()
    asyncio.run(Probe(args.output, args.inputs, args.candidate_only).run())
