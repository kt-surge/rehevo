"""Analyze all predeclared windows together; retain paired failures and usage."""
import argparse
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import numpy as np


ARMS = ('whole', 'frames')


def metrics(pairs):
    result = {}
    for arm in ARMS:
        values = np.array([pair[arm]['firstDeliveredPcmMs'] for pair in pairs], dtype=float)
        result[arm] = dict(n=len(values), p50Ms=float(np.quantile(values, .5, method='linear')),
                           p95Ms=float(np.quantile(values, .95, method='linear')))
    result['p50ImprovementPercent'] = 100 * (1-result['frames']['p50Ms']/result['whole']['p50Ms'])
    result['p95ImprovementPercent'] = 100 * (1-result['frames']['p95Ms']/result['whole']['p95Ms'])
    result['medianPairedDifferenceMs'] = float(np.median([
        pair['frames']['firstDeliveredPcmMs']-pair['whole']['firstDeliveredPcmMs'] for pair in pairs]))
    return result


def intervals(pairs, repetitions=10000):
    rng = np.random.default_rng(2026100407)
    values = np.array([[pair[arm]['firstDeliveredPcmMs'] for arm in ARMS] for pair in pairs])
    windows = sorted({pair['window'] for pair in pairs})
    texts = sorted({pair['whole']['textSha256'] for pair in pairs})
    by_window = [np.array([i for i,pair in enumerate(pairs) if pair['window']==window]) for window in windows]
    by_text = [np.array([i for i,pair in enumerate(pairs) if pair['whole']['textSha256']==text]) for text in texts]
    paired, clustered = [], []
    for _ in range(repetitions):
        sampled = np.concatenate([rng.choice(indices, len(indices), replace=True) for indices in by_window])
        percentiles = np.quantile(values[sampled], .95, axis=0, method='linear')
        paired.append(100 * (1-percentiles[1]/percentiles[0]))
        selected = rng.choice(len(by_text), len(by_text), replace=True)
        sampled = np.concatenate([by_text[index] for index in selected])
        percentiles = np.quantile(values[sampled], .95, axis=0, method='linear')
        clustered.append(100 * (1-percentiles[1]/percentiles[0]))
    return dict(repetitions=repetitions, randomSeed=2026100407,
                windowStratifiedPairedBootstrap95Percent=list(map(float,np.quantile(paired,[.025,.975]))),
                textClusterBootstrap95Percent=list(map(float,np.quantile(clustered,[.025,.975]))),
                limitations='Eight scripted texts and three windows; neither interval establishes broad production generalization')


def analyze(root):
    expected = {'window-1':34, 'window-2':33, 'window-3':33}
    errors, pairs, windows, source_versions = [], [], [], []
    usage = {arm:dict(input_tokens=0, output_tokens=0, total_tokens=0) for arm in ARMS}
    unknown_usage = []
    starts, audio_equal = [], 0
    configurations = []
    for name, count in expected.items():
        run = root/name
        report = json.loads((run/'results.json').read_text(encoding='utf-8-sig'))
        manifest = json.loads((run/'source-manifest.json').read_text(encoding='utf-8-sig'))
        starts.append(datetime.fromisoformat(manifest['frozenAt']))
        configurations.append({key:report[key] for key in ('sdkVersion','model','voice','format','speechRate','volume','language','seed','freshClientEveryCall')})
        versions = {}
        for source, item in manifest['sources'].items():
            actual = hashlib.sha256((run/'sources'/item['file']).read_bytes()).hexdigest()
            versions[source] = actual
            if actual != item['sha256']:
                errors.append(f'{name}: source mismatch {source}')
        source_versions.append(versions)
        if report.get('stoppedEarly') is not False or report['pairsRequested'] != count or len(report['results']) != 2*count:
            errors.append(f'{name}: incomplete window')
        table = {}
        for row in report['results']:
            table.setdefault(row['pair'], {})[row['mode']] = row
            audio = (run/row['audioPath']).read_bytes()
            if row['status'] != 'success' or not audio or len(audio)%2 or len(audio)!=row['bytes'] or hashlib.sha256(audio).hexdigest()!=row['audioSha256']:
                errors.append(f'{name}: audio/request failure {row["audioPath"]}')
            if sum(frame['bytes'] for frame in row['frames']) != len(audio):
                errors.append(f'{name}: frame bytes mismatch {row["audioPath"]}')
            if not isinstance(row['firstDeliveredPcmMs'],(float,int)) or not math.isfinite(row['firstDeliveredPcmMs']) or row['firstDeliveredPcmMs']<=0:
                errors.append(f'{name}: invalid first PCM clock')
            usage_events = row['sdkObservation']['rawProviderUsageEvents']
            distinct = {tuple(event.get(key) for key in ('requestId','input_tokens','output_tokens','total_tokens')) for event in usage_events}
            if len(distinct)!=1 or any(value is None for value in next(iter(distinct),(None,))):
                unknown_usage.append(dict(window=name,pair=row['pair'],mode=row['mode']))
            else:
                request_id, input_tokens, output_tokens, total_tokens = next(iter(distinct))
                if input_tokens+output_tokens != total_tokens:
                    errors.append(f'{name}: inconsistent actual usage {row["audioPath"]}')
                for key,value in zip(usage[row['mode']],(input_tokens,output_tokens,total_tokens)):
                    usage[row['mode']][key]+=value
        window_pairs = []
        first_order_by_text = {}
        for pair_index, arms in table.items():
            if set(arms)!=set(ARMS) or arms['whole']['textSha256'] != arms['frames']['textSha256']:
                errors.append(f'{name}: unpaired input {pair_index}'); continue
            input_sha = hashlib.sha256(arms['whole']['text'].encode('utf-8')).hexdigest()
            if input_sha != arms['whole']['textSha256']:
                errors.append(f'{name}: input hash {pair_index}')
            item = dict(window=name,pair=pair_index,**arms)
            window_pairs.append(item); pairs.append(item)
            audio_equal += arms['whole']['audioSha256'] == arms['frames']['audioSha256']
            order = [row['mode'] for row in report['results'] if row['pair']==pair_index]
            entry = first_order_by_text.setdefault(input_sha,dict(whole=0,frames=0))
            entry[order[0]]+=1
        for text_hash,counts in first_order_by_text.items():
            if abs(counts['whole']-counts['frames'])>1:
                errors.append(f'{name}: unbalanced per-text first order {text_hash}')
        windows.append(dict(name=name,startedAt=manifest['frozenAt'],finishedAt=report.get('finishedAt'),
                            requestedPairs=count,actualPairs=len(window_pairs),metrics=metrics(window_pairs),
                            firstOrderPerText=first_order_by_text))
    if any(config!=configurations[0] for config in configurations[1:]):
        errors.append('Model/audio configuration drift between windows')
    if any(version!=source_versions[0] for version in source_versions[1:]):
        errors.append('Production/experiment source drift between windows')
    gaps = [(later-earlier).total_seconds() for earlier,later in zip(starts,starts[1:])]
    if any(gap<300 for gap in gaps):
        errors.append('Window start gap below 300 seconds')
    if len(pairs)!=100:
        errors.append('Planned 100 pairs not complete')
    if audio_equal != len(pairs):
        errors.append('Nonidentical audio requires content/listening review before equivalence claim')
    all_metrics = metrics(pairs)
    noninitial = [pair for pair in pairs if not any(pair[arm]['processFirstInvocation'] for arm in ARMS)]
    quality_gate = not errors
    result = dict(scope='formal TTS component only; not complete voice interview or browser latency',
                  errors=errors,windows=windows,windowStartGapsSeconds=gaps,
                  configuration=configurations[0],pairCount=len(pairs),uniqueTexts=len({pair['whole']['textSha256'] for pair in pairs}),
                  requestCount=sum(len(json.loads((root/name/'results.json').read_text(encoding='utf-8'))['results']) for name in expected),
                  bitIdenticalAudioPairs=audio_equal,metrics=all_metrics,
                  excludingProcessFirstPairSensitivity=metrics(noninitial),
                  confidenceIntervals=intervals(pairs),usageTotalsDeduplicated=usage,unknownUsage=unknown_usage,
                  gates=dict(correctness=quality_gate,p95PointImprovementAtLeast20Percent=all_metrics['p95ImprovementPercent']>=20,
                             componentRetain=quality_gate and all_metrics['p95ImprovementPercent']>=20),
                  percentileDefinition='numpy quantile linear (type 7); all successful planned requests, process-first sensitivity reported separately',
                  usageRule='Deduplicate repeated identical requestId/token tuples; actual provider values only, missing remains unknown',
                  decisionBoundary='Component retention does not enable default frame protocol; actual browser/model and cancellation gates remain required')
    target=root/'formal-analysis.json'
    if target.exists(): raise ValueError('Preserve existing analysis')
    target.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({key:value for key,value in result.items() if key not in ('windows',)},ensure_ascii=False,indent=2))
    return bool(errors)


if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('run',type=Path)
    raise SystemExit(analyze(parser.parse_args().run))
