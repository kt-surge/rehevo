"""Audit all retained product/browser preflight attempts; no exclusion or P95 claim."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import wave


def read_rows(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]


def chat_usage(rows):
    snapshots = [row for row in rows if row['kind'] == 'metrics_snapshot' and row.get('httpStatus') == 200]
    result = []
    deltas, pending = [], None
    for row in snapshots:
        usage = {}
        for line in row['lines']:
            if line.startswith('gen_ai_client_token_usage_total{') and 'gen_ai_operation_name="chat"' in line:
                token_type = re.search(r'gen_ai_token_type="([^"]+)"', line).group(1)
                usage[token_type] = usage.get(token_type, 0) + float(line.rsplit(' ', 1)[1])
        result.append(dict(label=row['label'], at=row['at'], usage=usage))
        if row['label'] == 'before_submit':
            pending = (row['at'], usage)
        elif row['label'] == 'after_terminal' and pending is not None:
            observed = {key:usage.get(key, 0) - pending[1].get(key, 0)
                        for key in ('input', 'output', 'total')}
            deltas.append(dict(before=pending[0], after=row['at'],
                               usage=observed if observed['total'] > 0 else None,
                               status='observed_increment' if observed['total'] > 0 else 'unknown_no_final_usage'))
            pending = None
    return dict(snapshots=result, finalObserved=result[-1]['usage'] if result else None,
                missingUsageIsNotZero=True, countersArePerApplication=True,
                perSubmittedResponse=deltas,
                unknownSubmittedResponses=sum(row['usage'] is None for row in deltas),
                finalObservedIsNotCompleteBatchCost=True,
                relayStopSnapshotPresent=any(row['label'] == 'relay_stop' for row in snapshots))


def tts_usage(path):
    if not path.exists():
        return None
    latest = {}
    for row in read_rows(path):
        if row.get('kind') == 'usage_snapshot':
            latest[row['id']] = row
    requests = []
    sums = Counter()
    for ident, row in sorted(latest.items()):
        raw = row.get('rawProviderUsageEvents', [])
        final = raw[-1] if raw else None
        numeric = {key: final[key] for key in ('input_tokens', 'output_tokens', 'total_tokens')
                   if final and isinstance(final.get(key), (float, int))}
        sums.update(numeric)
        requests.append(dict(id=ident, input=row.get('input'), usage=numeric or None,
                             rawUsageEvents=len(raw), sdkProviderInvocations=row.get('providerInvocations'),
                             localClose=row.get('closeRequests'), localCancel=row.get('cancelRequests'),
                             remoteCloseAcknowledgementObserved=row.get('remoteCloseAcknowledgementObserved')))
    return dict(requests=requests, observedNumericTotals=dict(sums),
                unknownRequests=sum(request['usage'] is None for request in requests),
                aggregation='last cumulative numeric event per synthesizer; duplicate events not summed')


def relay_audit(directory):
    rows = read_rows(directory / 'events.jsonl')
    turns, current, before_submit = {}, {}, {}
    reconnects, failures, audio_errors = [], [], []
    pending_reconnect = {}
    for row in rows:
        sid = row.get('sessionId')
        direction = row['kind']
        if direction == 'client_event' and row.get('action') == 'submit':
            before_submit[sid] = row
        if direction == 'client_event' and row.get('action') == 'reconnect_asr':
            pending_reconnect[sid] = row
        if direction == 'server_event' and row.get('action') == 'asr_ready' and sid in pending_reconnect:
            start = pending_reconnect.pop(sid)
            reconnects.append(dict(sessionId=sid, start=start['at'], ready=row['at'],
                                   relayControlToReadyMs=(row['monotonicSeconds'] - start['monotonicSeconds']) * 1000,
                                   recognitionNotMeasured=True))
        if direction == 'server_event' and row.get('action') == 'turn_started':
            key = (sid, row['turnId'])
            current[sid] = key
            submit = before_submit.pop(sid, None)
            turns[key] = dict(sessionId=sid, turnId=row['turnId'], opening=submit is None,
                              serverStartedAt=row['at'], submitAt=submit['at'] if submit else None,
                              events=[], audio=[], diagnostics=[], playback=[], cancellations=[])
        key = (sid, row.get('turnId') or row.get('data', {}).get('turnId'))
        if key not in turns:
            key = current.get(sid)
        turn = turns.get(key)
        if direction == 'server_event' and row.get('type') == 'error' or direction in ('relay_failed', 'metrics_failed'):
            failures.append(row)
        if not turn:
            continue
        if direction == 'server_event':
            turn['events'].append({key:value for key,value in row.items()
                                   if key not in ('data', 'arm', 'kind')})
        if direction == 'client_event' and row.get('action') == 'playback_observed':
            turn['playback'].append(dict(at=row['at'], **row['data']))
        if direction == 'client_event' and row.get('action') == 'playback_diagnostics':
            turn['diagnostics'].append(dict(at=row['at'], **row['data']))
        if direction == 'client_event' and row.get('action') in ('cancel', 'cancel_acknowledged'):
            turn['cancellations'].append(row)
        if direction == 'server_event' and row.get('audioFile'):
            raw = (directory / row['audioFile']).read_bytes()
            if len(raw) != row['audioBytes'] or hashlib.sha256(raw).hexdigest() != row['audioSha256']:
                audio_errors.append(dict(file=row['audioFile'], reason='audio hash or byte count mismatch'))
            duration = None
            if row['type'] in ('audio', 'audio_chunk'):
                with wave.open(str(directory / row['audioFile']), 'rb') as audio:
                    assert audio.getnchannels() == 1 and audio.getsampwidth() == 2 and audio.getframerate() == 24000
                    duration = audio.getnframes() / audio.getframerate()
            elif row['type'] == 'audio_frame':
                if raw:
                    assert len(raw) % 2 == 0
                    duration = len(raw) / 48000
            turn['audio'].append(dict(file=row['audioFile'], bytes=len(raw), type=row['type'],
                                       sentenceIndex=row.get('sentenceIndex'), frameIndex=row.get('frameIndex'),
                                       endOfSentence=row.get('endOfSentence'), durationSeconds=duration))
    for turn in turns.values():
        events = turn.pop('events')
        turn['serverActions'] = [row['action'] for row in events if row.get('action')]
        final = [row for row in events if row.get('type') == 'text' and row.get('final')]
        turn['finalText'] = final[-1]['content'] if final else None
        turn['finalCharacters'] = len(turn['finalText']) if turn['finalText'] else None
        scheduled = [row for row in turn['diagnostics'] if row['kind'] == 'scheduled']
        turn['scheduledChunks'] = len(scheduled)
        gaps = [row['gapMs'] for row in scheduled if 'gapMs' in row]
        turn['scheduledGapMs'] = dict(sum=sum(gaps), max=max(gaps) if gaps else None,
                                       notAcousticMeasurement=True)
        cancels = [row for row in turn['cancellations'] if row['action'] == 'cancel']
        turn['validOutputBeforeCancel'] = any(
            playback['playbackMode'] == 'output_pcm' and playback['at'] < cancel['at']
            for playback in turn['playback'] for cancel in cancels)
        if cancels:
            at = cancels[-1]['at']
            turn['lateScheduledOrStartedAfterCancel'] = [row for row in turn['diagnostics']
                if row['at'] > at and row['kind'] in ('started', 'scheduled')]
            turn['lateAudioServerAfterCancel'] = [row for row in events
                if row['at'] > at and row.get('type') in ('audio', 'audio_frame', 'audio_chunk')]
            turn['serverCancelConfirmations'] = [row for row in events
                if row['at'] > at and row.get('action') in ('cancel_confirmed', 'turn_cancelled')]
            turn['firstFollowingEventSeconds'] = max(
                (datetime.fromisoformat(row['at']) - datetime.fromisoformat(at)).total_seconds()
                for row in rows if row.get('sessionId') == turn['sessionId'] and row['at'] >= at)
        sequences = [row['sequence'] for row in events if isinstance(row.get('sequence'), int)]
        turn['outboundSequenceStrictlyIncreasing'] = all(a < b for a,b in zip(sequences, sequences[1:]))
        frames = [row for row in events if row.get('type') == 'audio_frame']
        frame_ids = [(row['sentenceIndex'], row['frameIndex']) for row in frames]
        turn['frameIdsUnique'] = len(frame_ids) == len(set(frame_ids))
        turn['sentenceEosCount'] = sum(bool(row.get('endOfSentence')) for row in frames)
    return dict(plan=json.loads((directory/'plan.json').read_text(encoding='utf-8')),
                totalEvents=len(rows), createdSessions=[row['response']['data']['sessionId'] for row in rows
                    if row['kind']=='session_create' and row.get('response',{}).get('code')==200],
                eventKinds=dict(Counter(row['kind'] for row in rows)), turns=list(turns.values()),
                reconnects=reconnects, failures=failures, audioErrors=audio_errors, chatUsage=chat_usage(rows))


def main(root, output_name):
    report = dict(analyzedAt=datetime.now(timezone.utc).isoformat(), formalAB=False,
                  analyzerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  scope='all preflight samples retained; microphone and human end-of-speech timing not measured',
                  attempts=[])
    for attempt in sorted(root.glob('browser-preflight-r*')):
        arms = []
        for relay in sorted(attempt.glob('relay-*')):
            arm = relay.name.removeprefix('relay-')
            value = relay_audit(relay)
            value['ttsUsage'] = tts_usage(attempt/f'backend-{arm}'/'tts-usage.jsonl')
            arms.append(dict(arm=arm, **value))
        report['attempts'].append(dict(attempt=attempt.name, arms=arms))
    if Path(output_name).name != output_name or not output_name.endswith('.json'):
        raise ValueError('Output name must be one JSON filename')
    output = root / output_name
    if output.exists():
        raise ValueError('Analysis exists; preserve prior analysis and choose a new run')
    output.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    for attempt in report['attempts']:
        for arm in attempt['arms']:
            print(json.dumps(dict(attempt=attempt['attempt'],arm=arm['arm'],
                                  turns=len(arm['turns']), reconnects=len(arm['reconnects']),
                                  playback=[playback['submitToPlaybackStartMs'] for turn in arm['turns']
                                            for playback in turn['playback']],
                                  validCancels=sum(turn['validOutputBeforeCancel'] for turn in arm['turns']),
                                  audioErrors=arm['audioErrors'],chat=arm['chatUsage']['finalObserved'],
                                  tts=arm['ttsUsage']['observedNumericTotals'] if arm['ttsUsage'] else None,
                                  ttsUnknown=arm['ttsUsage']['unknownRequests'] if arm['ttsUsage'] else None)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('root', type=Path)
    parser.add_argument('--output-name', default='preflight-analysis.json')
    args = parser.parse_args()
    main(args.root, args.output_name)
