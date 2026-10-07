"""Actual public PCM -> application WS -> ASR -> subtitles, no LLM submission."""
import argparse
import asyncio
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import shutil
import sys
import time
import aiohttp

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0,str(ROOT/'observability/experiments/rag-evaluation'))
from experimental_index_snapshot import current, assert_scope, vectors_hash, query
from ingest_primary_dev import provider_snapshot
from review_audio_content import pcm16, normalize_text

BASE='http://127.0.0.1:18080'
OLD=HERE/'runs/20261004-r4'


def save(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2),encoding='utf-8')


def scope():
    value=current();assert_scope(value)
    counts=query("SELECT json_build_object('documents',(SELECT count(*) FROM knowledge_bases),'vectors',(SELECT count(*) FROM vector_store),'voiceSessions',(SELECT count(*) FROM voice_interview_sessions),'ragSessions',(SELECT count(*) FROM rag_chat_sessions),'ragMessages',(SELECT count(*) FROM rag_chat_messages));")
    return dict(publicVectorSha256=vectors_hash(value),counts=counts)


async def api(client,method,path,**kwargs):
    async with client.request(method,BASE+path,**kwargs) as response:
        value=await response.json()
        if response.status!=200 or value.get('code')!=200:
            raise ValueError('Application API failed: '+path)
        return value.get('data')


async def meters(client):
    async with client.get(BASE+'/actuator/prometheus') as response:
        response.raise_for_status();value=await response.text()
    return '\n'.join(x for x in value.splitlines() if x.startswith('gen_ai_client_token_usage_total{'))+'\n'


async def main(args):
    if args.output.exists():raise ValueError('Preserve previous run')
    before=scope()
    if before['counts']!=dict(documents=15,vectors=123,voiceSessions=0,ragSessions=0,ragMessages=0):
        raise ValueError('Unexpected data scope')
    frozen= json.loads((OLD/'artifacts.sha256.json').read_text(encoding='utf-8'))['files']
    rel='window-2/002-whole.pcm'; source=OLD/rel
    raw=source.read_bytes()
    if hashlib.sha256(raw).hexdigest()!=frozen[rel]:raise ValueError('Original sealed audio differs')
    audio=pcm16(raw)
    args.output.mkdir()
    shutil.copy2(Path(__file__),args.output/'driver.py')
    shutil.copy2(HERE/'review_audio_content.py',args.output/'audio_transform.py')
    shutil.copy2(HERE/'APPLICATION_ASR_PROTOCOL_2026-10-05.md',args.output/'PROTOCOL.md')
    sources=[]
    directory=args.output/'source';directory.mkdir()
    paths=[*ROOT.glob('app/src/main/java/interview/guide/modules/voiceinterview/**/*.java'),
        ROOT/'app/src/main/resources/application.yml',ROOT/'gradle/libs.versions.toml',
        ROOT/'observability/experiments/runtime/boot-run.ps1']
    for index,path in enumerate(paths):
        name=f'{index:03d}.source';shutil.copy2(path,directory/name)
        sources.append(dict(file=name,original=path.relative_to(ROOT).as_posix(),sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    save(directory/'manifest.json',dict(sources=sources))
    (args.output/'input-16000.pcm').write_bytes(audio)
    save(args.output/'plan.json',dict(expectedModel=args.model,source=source.relative_to(ROOT).as_posix(),
        sourceSha256=frozen[rel],pcmSha256=hashlib.sha256(audio).hexdigest(),pcmBytes=len(audio),sampleRate=16000,
        frameBytes=3200,frameIntervalMs=100,tailSilenceMs=3000,maxSessions=1,
        expectedText='你的服务如何处理重复消息？如果数据库提交成功，但消息确认前进程退出，会发生什么？',
        expectedTextSentToAsr=False,submittedToLlm=False,idleReconnect=args.idle_reconnect,
        repeatBeforeIdle=args.repeat_before_idle,
        boundary='Actual application ASR path; synthetic controlled PCM, no browser/microphone/output-quality claim.'))
    save(args.output/'scope-before.json',before)
    providers=provider_snapshot()
    save(args.output/'provider-before.json',providers)
    if providers['voice']['asr']['model']!=args.model:raise ValueError('Runtime model differs')
    events=[];session_id=None;outcome={};began=time.monotonic();first_audio=None;last_audio=None
    first_audio_at=None;last_audio_at=None
    application_log=ROOT/'data/local/rehevo-opt-runtime-20261001/boot-run.log'
    log_start=application_log.stat().st_size
    async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=75)) as client:
        (args.output/'metrics-before.txt').write_text(await meters(client),encoding='utf-8')
        try:
            session=await api(client,'POST','/api/voice-interview/sessions',json=dict(skillId='java-backend',difficulty='junior',
                introEnabled=False,techEnabled=True,projectEnabled=False,hrEnabled=False,plannedDuration=30,llmProvider='dashscope'))
            save(args.output/'created-session.json',session)
            session_id=str(session['sessionId'])
            ready=asyncio.Event();cancelled=asyncio.Event();unavailable=asyncio.Event()
            async with client.ws_connect(BASE.replace('http:','ws:')+'/ws/voice-interview/'+session_id,max_msg_size=8*1024*1024) as ws:
                async def receive():
                    async for message in ws:
                        if message.type!=aiohttp.WSMsgType.TEXT:continue
                        value=json.loads(message.data);elapsed=(time.monotonic()-began)*1000
                        kept=dict(value,observedMs=elapsed,observedAt=datetime.now(timezone.utc).isoformat())
                        if isinstance(kept.get('data'),str) and kept.get('type') in ['audio','audio_chunk','audio_frame']:
                            binary=base64.b64decode(kept.pop('data'));kept.update(audioBytes=len(binary),audioSha256=hashlib.sha256(binary).hexdigest())
                        events.append(kept);save(args.output/'events.json',events)
                        if value.get('action')=='asr_ready':ready.set()
                        if value.get('action') in ['asr_unavailable'] or value.get('type')=='error':unavailable.set()
                        if value.get('action')=='turn_started':
                            await ws.send_json(dict(type='control',action='cancel',data=dict(cancelRequestId='controlled-asr-opening-cancel')))
                        if value.get('action') in ['turn_cancelled','cancel_confirmed']:cancelled.set()
                receiver=asyncio.create_task(receive())
                try:
                    for _ in range(80):
                        if unavailable.is_set():break
                        if ready.is_set() and cancelled.is_set():break
                        await asyncio.sleep(.1)
                    if unavailable.is_set():
                        outcome=dict(status='asr-unavailable',audioFramesSent=0)
                    elif not ready.is_set() or not cancelled.is_set():
                        outcome=dict(status='ready-or-opening-cancel-timeout',audioFramesSent=0)
                    else:
                        with_tail=audio+b'\0'*(16000*2*3)
                        count=0;first_audio=(time.monotonic()-began)*1000
                        first_audio_at=datetime.now(timezone.utc).isoformat()
                        for offset in range(0,len(with_tail),3200):
                            if unavailable.is_set():break
                            await ws.send_json(dict(type='audio',data=base64.b64encode(with_tail[offset:offset+3200]).decode()))
                            count+=1
                            if offset < len(audio):
                                last_audio=(time.monotonic()-began)*1000
                                last_audio_at=datetime.now(timezone.utc).isoformat()
                            await asyncio.sleep(.1)
                        await asyncio.sleep(1)
                        subtitles=[x for x in events if x.get('type')=='subtitle' and x.get('text')]
                        outcome=dict(status='subtitle-observed' if subtitles else 'no-subtitle',audioFramesSent=count,
                            firstAudioSendMs=first_audio,lastSpeechFrameSendMs=last_audio,
                            firstSubtitleMs=subtitles[0]['observedMs'] if subtitles else None,
                            lastSubtitleMs=subtitles[-1]['observedMs'] if subtitles else None,
                            subtitleText=subtitles[-1].get('text') if subtitles else None,
                            firstAudioSendAt=first_audio_at,lastSpeechFrameSendAt=last_audio_at,
                            asrFinalDistinguished=False)
                        expected_repetitions=1
                        if args.repeat_before_idle and not unavailable.is_set():
                            before_repeat=len([x for x in events if x.get('type')=='subtitle'])
                            for offset in range(0,len(with_tail),3200):
                                if unavailable.is_set():break
                                await ws.send_json(dict(type='audio',data=base64.b64encode(with_tail[offset:offset+3200]).decode()))
                                await asyncio.sleep(.1)
                            await asyncio.sleep(1)
                            repeats=[x for x in events if x.get('type')=='subtitle'][before_repeat:]
                            outcome['sameConnectionRepeatedSubtitleText']=repeats[-1].get('text') if repeats else None
                            expected_repetitions=2
                        if args.idle_reconnect:
                            # This deliberate idle observation does not submit an answer to the LLM.
                            idle_started=time.monotonic()
                            for _ in range(300):
                                if unavailable.is_set():break
                                await asyncio.sleep(.1)
                            idle_ms=(time.monotonic()-idle_started)*1000
                            outcome['idleUnavailableObserved']=unavailable.is_set()
                            outcome['idleObservationMs']=idle_ms
                            if unavailable.is_set():
                                ready.clear();unavailable.clear()
                                reconnected=time.monotonic()
                                await ws.send_json(dict(type='control',action='reconnect_asr'))
                                for _ in range(80):
                                    if ready.is_set() or unavailable.is_set():break
                                    await asyncio.sleep(.1)
                                outcome['reconnectReady']=ready.is_set()
                                outcome['reconnectReadyObservedMs']=(time.monotonic()-reconnected)*1000
                                prior_subtitle_count=len([x for x in events if x.get('type')=='subtitle'])
                                if ready.is_set() and not unavailable.is_set():
                                    for offset in range(0,len(with_tail),3200):
                                        if unavailable.is_set():break
                                        await ws.send_json(dict(type='audio',data=base64.b64encode(with_tail[offset:offset+3200]).decode()))
                                        await asyncio.sleep(.1)
                                    await asyncio.sleep(1)
                                    after_subtitles=[x for x in events if x.get('type')=='subtitle'][prior_subtitle_count:]
                                    outcome['reconnectAudioSubtitleText']=after_subtitles[-1].get('text') if after_subtitles else None
                                    outcome['reconnectAudioSubtitleObserved']=bool(after_subtitles)
                                    outcome['expectedCombinedRepetitions']=expected_repetitions+1
                finally:
                    await ws.close();receiver.cancel()
                    received=await asyncio.gather(receiver,return_exceptions=True)
                    errors=[x for x in received if isinstance(x,Exception)]
                    if errors:outcome.update(status='receiver-failed',receiverErrorType=type(errors[0]).__name__)
            await asyncio.sleep(.5)
            save(args.output/'session-after.json',await api(client,'GET','/api/voice-interview/sessions/'+session_id))
            save(args.output/'history-after.json',await api(client,'GET','/api/voice-interview/sessions/'+session_id+'/messages'))
        except Exception as error:
            outcome.update(status='probe-failed',errorType=type(error).__name__,error=str(error))
        finally:
            (args.output/'metrics-after.txt').write_text(await meters(client),encoding='utf-8')
            if session_id:
                await api(client,'DELETE','/api/voice-interview/sessions/'+session_id)
    after=scope();save(args.output/'scope-after.json',after)
    excerpt=application_log.read_bytes()[log_start:].decode('utf-8',errors='strict')
    (args.output/'application-log-excerpt.txt').write_text(excerpt,encoding='utf-8')
    if before!=after:raise ValueError('Isolated data scope/fingerprint changed after own-session cleanup')
    plan=json.loads((args.output/'plan.json').read_text(encoding='utf-8'))
    outcome.update(model=args.model,submittedToLlm=False,finalUsage='unknown-not-exposed-by-application-WS',
        automaticOpeningMaySynthesizeTts=True,originalPublicFingerprintUnchanged=True,ownSessionDeleted=True,
        normalizedExactTextMatch=bool(outcome.get('subtitleText')) and normalize_text(outcome['subtitleText'])==normalize_text(plan['expectedText']),
        finishedAt=datetime.now(timezone.utc).isoformat())
    if outcome.get('reconnectAudioSubtitleText'):
        outcome['normalizedReconnectedCombinedTextMatch']=normalize_text(outcome['reconnectAudioSubtitleText'])==normalize_text(plan['expectedText']*outcome['expectedCombinedRepetitions'])
    if outcome.get('sameConnectionRepeatedSubtitleText'):
        outcome['normalizedSameConnectionRepeatedTextMatch']=normalize_text(outcome['sameConnectionRepeatedSubtitleText'])==normalize_text(plan['expectedText']*2)
    save(args.output/'result.json',outcome);print(json.dumps(outcome,ensure_ascii=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--model',choices=['qwen-audio-3.0-asr-flash-streaming','qwen-audio-3.1-asr-flash-streaming'],required=True)
    parser.add_argument('--idle-reconnect',action='store_true')
    parser.add_argument('--repeat-before-idle',action='store_true')
    asyncio.run(main(parser.parse_args()))
