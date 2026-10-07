"""Structural/usage audit, separate from semantic self-review and formal latency."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import sys
import wave

sys.path.insert(0, str(Path(__file__).resolve().parent))
from analyze_real_preflight import tts_usage


def main(output, name):
    rows = [json.loads(x) for x in (output/'events.jsonl').read_text(encoding='utf-8').splitlines()]
    answers = [r for r in rows if r['kind']=='terminal' and not r['label'].endswith('-opening')]
    snapshots = {}
    for row in rows:
        if row['kind'] != 'metrics': continue
        usage = {}
        for line in row['lines']:
            if line.startswith('gen_ai_client_token_usage_total{') and 'gen_ai_operation_name="chat"' in line:
                token = re.search(r'gen_ai_token_type="([^"]+)"', line).group(1)
                usage[token] = usage.get(token,0) + float(line.rsplit(' ',1)[1])
        snapshots[(row['arm'], row['label'])] = usage
    for row in answers:
        text = row['text'] or ''
        row['structurallyComplete'] = row['terminal']=='turn_completed' and 0<len(text)<=120 and text.endswith(('？','?')) and '…' not in text
        row['questionMarks'] = text.count('？') + text.count('?')
        before = snapshots[(row['arm'],row['label']+'-before')]
        after = snapshots[(row['arm'],row['label']+'-after')]
        delta = {key:after.get(key,0)-before.get(key,0) for key in ('input','output','total')}
        row['chatUsage'] = delta if delta['total']>0 else None
    audio = []
    for row in rows:
        event = row.get('event',{})
        if 'audioFile' not in event: continue
        path = output/event['audioFile']
        raw = path.read_bytes()
        assert hashlib.sha256(raw).hexdigest()==event['audioSha256'] and len(raw)==event['audioBytes']
        with wave.open(str(path),'rb') as file:
            channels, width, rate, frames = file.getnchannels(), file.getsampwidth(),file.getframerate(),file.getnframes()
        assert (channels,width,rate)==(1,2,24000) and frames>0
        audio.append(dict(arm=row['arm'],label=row['label'],file=event['audioFile'],frames=frames,
                          durationSeconds=frames/rate,sha256=event['audioSha256']))
    totals = {}
    for arm in ['baseline','candidate']:
        subset = [r for r in answers if r['arm']==arm]
        sums = Counter()
        for row in subset:
            if row['chatUsage']: sums.update(row['chatUsage'])
        totals[arm] = dict(answers=len(subset),completed=sum(r['terminal']=='turn_completed' for r in subset),
            structurallyComplete=sum(r['structurallyComplete'] for r in subset),
            multiQuestion=sum(r['questionMarks']>1 for r in subset),
            chatObserved=dict(sums),unknownChatResponses=sum(r['chatUsage'] is None for r in subset))
    result = dict(scope='small actual REST/WS quality preflight; no browser playback/P95 or independent human blind review',
        answers=answers,totals=totals,audio=audio,
        semanticGate='requires explicit self-review, structural validity is insufficient',
        missingUsageIsNotZero=True,ttsAllAttemptedAppInvocations=dict(
            baseline=tts_usage(output.parent/'baseline-app-r2/tts-usage.jsonl'),
            candidate=tts_usage(output.parent/'candidate-app/tts-usage.jsonl')))
    target = output/name
    with target.open('x',encoding='utf-8') as file: json.dump(result,file,ensure_ascii=False,indent=2)
    print(json.dumps(dict(totals=totals,audioFiles=len(audio)),ensure_ascii=False))


if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('output',type=Path)
    parser.add_argument('--name',default='analysis.json')
    args=parser.parse_args()
    main(args.output,args.name)
