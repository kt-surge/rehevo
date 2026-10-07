"""Independent ASR check of synthetic TTS differences; no human listening claim."""
import argparse
import asyncio
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import time
import unicodedata
import uuid
import wave
import aiohttp
import numpy as np
from scipy.signal import resample_poly


MODEL = 'qwen-audio-3.1-asr-flash-streaming'
URL = 'wss://dashscope.aliyuncs.com/api-ws/v1/inference'


def normalize_text(text):
    normalized = unicodedata.normalize('NFKC', text).casefold()
    normalized = normalized.replace('二十一', '21').replace('百分之九十五', '95')
    return ''.join(char for char in normalized
                   if not char.isspace() and not unicodedata.category(char).startswith(('P', 'S')))


def cumulative_usage(events):
    latest, source, issues = None, None, []
    fields = ('input_tokens', 'output_tokens', 'total_tokens')
    for index, event in enumerate(events):
        usage = event.get('usage') or {}
        if not all(isinstance(usage.get(field), int) and usage[field] >= 0 for field in fields):
            continue
        if usage['input_tokens'] + usage['output_tokens'] != usage['total_tokens']:
            issues.append(dict(event=index, problem='input plus output differs from total'))
            continue
        if latest and any(usage[field] < latest[field] for field in fields):
            issues.append(dict(event=index, problem='cumulative token count decreased'))
        latest, source = usage, index
    return dict(finalUsage=latest, finalUsageSourceEvent=source, usageIssues=issues,
                usageConvention='latest valid cumulative event; intermediate usage is not summed')


def redact(value, key):
    if isinstance(value, str):
        return value.replace(key, '[REDACTED]')
    if isinstance(value, dict):
        return {name:redact(item, key) for name,item in value.items()}
    if isinstance(value, list):
        return [redact(item, key) for item in value]
    return value


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def pcm16(raw):
    samples = np.frombuffer(raw, dtype='<i2').astype(np.float64)
    resampled = np.rint(resample_poly(samples, 2, 3)).clip(-32768, 32767).astype('<i2')
    return resampled.tobytes()


async def transcribe(key, audio, target):
    task_id = str(uuid.uuid4())
    started = asyncio.Event()
    began = time.monotonic()
    final_sentences = {}
    report = dict(model=MODEL, sampleRate=16000, frameBytes=3200, tailSilenceMs=500,
                  pcmBytes=len(audio), pcmSha256=hashlib.sha256(audio).hexdigest(), taskId=task_id,
                  startedAt=datetime.now(timezone.utc).isoformat(), events=[], status='pending')
    try:
        timeout = aiohttp.ClientTimeout(total=60, connect=15)
        async with aiohttp.ClientSession(timeout=timeout, headers={'Authorization':'Bearer '+key}) as session:
            async with session.ws_connect(URL, heartbeat=20, max_msg_size=2**20) as socket:
                await socket.send_json(dict(header=dict(action='run-task', task_id=task_id, streaming='duplex'),
                    payload=dict(task_group='audio', task='asr', function='recognition', model=MODEL,
                                 parameters=dict(format='pcm', sample_rate=16000, language_hints=['zh']), input={})))

                async def receive():
                    async for message in socket:
                        if message.type != aiohttp.WSMsgType.TEXT:
                            continue
                        event = json.loads(message.data)
                        header, payload = event.get('header',{}), event.get('payload',{})
                        if header.get('task_id') != task_id:
                            raise ValueError('ASR task identity mismatch')
                        sentence = payload.get('output',{}).get('sentence') or {}
                        usage = payload.get('usage') or payload.get('output',{}).get('usage')
                        kept = dict(atMs=(time.monotonic()-began)*1000,
                                    header={k:v for k,v in header.items() if k in ('event','task_id','request_id','error_code','error_message')},
                                    sentence={k:v for k,v in sentence.items() if k in ('sentence_id','begin_time','end_time','text','sentence_end','heartbeat')},
                                    usage={k:v for k,v in (usage or {}).items() if k in ('input_tokens','output_tokens','total_tokens','duration')})
                        kept = redact(kept, key)
                        report['events'].append(kept); save(target, report)
                        if header.get('event') == 'task-started':
                            started.set()
                        if header.get('event') == 'task-failed':
                            raise RuntimeError(str(header.get('error_code'))+': '+str(header.get('error_message')).replace(key,'[REDACTED]'))
                        if sentence.get('sentence_end') and not sentence.get('heartbeat'):
                            final_sentences[sentence.get('sentence_id',len(final_sentences))]=sentence.get('text','')
                        if header.get('event') == 'task-finished':
                            report.update(cumulative_usage(report['events']))
                            return
                    raise RuntimeError('ASR connection ended before task-finished')

                async def send_audio():
                    await asyncio.wait_for(started.wait(),12)
                    with_tail = audio+b'\0'*16000
                    for offset in range(0,len(with_tail),3200):
                        await socket.send_bytes(with_tail[offset:offset+3200])
                        await asyncio.sleep(.1)
                    await socket.send_json(dict(header=dict(action='finish-task',task_id=task_id,streaming='duplex'),payload=dict(input={})))

                tasks = [asyncio.create_task(receive()),asyncio.create_task(send_audio())]
                try:
                    await asyncio.wait_for(asyncio.gather(*tasks),len(audio)/32000+30)
                finally:
                    for task in tasks:
                        if not task.done(): task.cancel()
                    await asyncio.gather(*tasks,return_exceptions=True)
        report['transcript']=''.join(final_sentences[index] for index in sorted(final_sentences))
        report['status']='success' if report['transcript'] else 'empty-transcript'
    except Exception as error:
        report['status']='failed'; report['errorType']=type(error).__name__
        report['errorMessage']=str(error).replace(key,'[REDACTED]')
    report['elapsedMs']=(time.monotonic()-began)*1000
    report['finishedAt']=datetime.now(timezone.utc).isoformat()
    save(target,report)
    return report


async def main(args):
    key=os.environ.get('REHEVO_TTS_EXPERIMENT_API_KEY') or os.environ.get('AI_BAILIAN_API_KEY')
    if not key: raise ValueError('Existing credential unavailable; do not export it')
    args.output.mkdir(parents=True,exist_ok=False)
    source_bytes = Path(__file__).read_bytes()
    (args.output/'source.py').write_bytes(source_bytes)
    differences=json.loads((args.study/'audio-differences.json').read_text(encoding='utf-8'))
    inputs=[]
    for difference in differences:
        for arm in ('whole','frames'):
            inputs.append(dict(window=difference['window'],pair=difference['pair'],arm=arm,
                               expectedText=difference['text'],source=difference['paths'][arm]))
    inputs=inputs[:args.limit]
    save(args.output/'plan.json',dict(model=MODEL,url=URL,inputs=inputs,
         scope='ASR content check of synthetic TTS only; no human listening, MOS or product ASR latency',
         transformation='scipy.signal.resample_poly 2/3, round/clip to PCM s16le; 500ms silence padding',
         scipyVersion=__import__('scipy').__version__,numpyVersion=np.__version__,goldNotSentToAsr=True,
         normalization='NFKC, casefold, remove whitespace/punctuation/symbols; 二十一 -> 21, 百分之九十五 -> 95',
         exactContentGate='normalized transcript equals normalized controlled input; retain all mismatches for review',
         usageConvention='latest valid cumulative event, never sum intermediate usage',
         sourceSha256=hashlib.sha256(source_bytes).hexdigest()))
    results=[]
    for index, item in enumerate(inputs):
        raw=Path(item['source']).read_bytes(); converted=pcm16(raw)
        (args.output/f'{index:02d}.pcm').write_bytes(converted)
        with wave.open(str(args.output/f'{index:02d}-original.wav'),'wb') as wav:
            wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(24000); wav.writeframes(raw)
        report=await transcribe(key,converted,args.output/f'{index:02d}-asr.json')
        results.append(dict(**item,originalSha256=hashlib.sha256(raw).hexdigest(),
                            report=f'{index:02d}-asr.json',status=report['status'],
                            transcript=report.get('transcript'),finalUsage=report.get('finalUsage'),
                            usageIssues=report.get('usageIssues'),
                            normalizedExactMatch=normalize_text(report.get('transcript','')) == normalize_text(item['expectedText'])))
        save(args.output/'results.json',results)
        print(json.dumps(dict(index=index,status=report['status'],transcript=report.get('transcript'),usage=report.get('finalUsage')),ensure_ascii=False),flush=True)
        if report['status']!='success': return 2
    return 0


if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('--study',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True); parser.add_argument('--limit',type=int,choices=range(1,11),default=10)
    raise SystemExit(asyncio.run(main(parser.parse_args())))
