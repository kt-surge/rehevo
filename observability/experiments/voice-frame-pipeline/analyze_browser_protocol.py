"""Actual-browser controlled protocol observations; no model/performance claim."""
import argparse
import hashlib
import json
from pathlib import Path


def analyze(run):
    events = [json.loads(line) for line in (run / 'events.jsonl').read_text(encoding='utf-8').splitlines()]
    scenarios = [event for event in events if event['kind'] == 'turn_scenario']
    traces, seen = [], set()
    local_logs = json.loads((run / 'browser-console.json').read_text(encoding='utf-8')) if (run / 'browser-console.json').exists() else []
    values = [event['data'] for event in events if event.get('action') == 'playback_diagnostics']
    for entry in local_logs:
        prefix = 'RehevoAudioDiagnostic '
        if prefix in entry['message']:
            values.append(json.loads(entry['message'].split(prefix, 1)[1]))
    for value in values:
        key = json.dumps(value, sort_keys=True)
        if key not in seen:
            traces.append(value); seen.add(key)
    output, errors = [], []
    for scenario in scenarios:
        turn = scenario['turnId']
        turn_traces = [trace for trace in traces if trace['turnId'] == turn]
        samples = [event['data'] for event in events if event.get('action') == 'playback_observed' and event['data']['turnId'] == turn]
        scheduled = [trace for trace in turn_traces if trace['kind'] == 'scheduled']
        cancelled = [trace for trace in turn_traces if trace['kind'] == 'cancelled']
        drained = [trace for trace in turn_traces if trace['kind'] == 'drained']
        gaps = [trace['gapMs'] for trace in scheduled]
        normal = scenario['mode'] in ('wav', 'frames', 'late-cancel-confirmation')
        valid_sample = len(samples) == 1 and 0 <= samples[0]['submitToAudioReceivedMs'] <= samples[0]['submitToPlaybackStartMs'] <= 120000
        if normal and (len(drained) != 1 or not valid_sample):
            errors.append(f'{turn}: normal completion/sample gate failed')
        if len(samples) > 1:
            errors.append(f'{turn}: duplicate playback sample')
        late = []
        if cancelled:
            cancelled_index = turn_traces.index(cancelled[0])
            late = [trace for trace in turn_traces[cancelled_index+1:] if trace['kind'] in ('scheduled','started','ended','drained')]
            if late:
                errors.append(f'{turn}: playback callback after cancel')
        for trace in scheduled:
            if trace['durationSeconds'] <= 0 or trace['gapMs'] < 0:
                errors.append(f'{turn}: invalid audio schedule')
        output.append(dict(turnId=turn, scenario=scenario['mode'], scheduledSources=len(scheduled),
                           maximumTimelineGapMs=max(gaps, default=None), drained=len(drained),
                           playbackReports=samples, cancelObservations=cancelled,
                           latePlaybackCallbacks=late))
    manifests = []
    for manifest_path in run.rglob('source-manifest.json'):
        manifest = json.loads(manifest_path.read_text(encoding='utf-8-sig'))
        for item in manifest.get('files', []):
            match = hashlib.sha256((manifest_path.parent/item['frozen']).read_bytes()).hexdigest() == item['sha256']
            manifests.append(dict(path=item['path'], match=match))
            if not match:
                errors.append('source hash mismatch: '+item['path'])
    result = dict(evidenceType='controlled actual React browser; no models/microphone/physical output measurement',
                  errors=errors, scenarios=output, sourceVerification=manifests,
                  serverEvents=len(events), localConsoleRecords=len(local_logs))
    target = run/'analysis.json'
    if target.exists():
        raise ValueError('Preserve previous analysis; choose a new filename/run')
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({key:value for key,value in result.items() if key != 'sourceVerification'}, ensure_ascii=False, indent=2))
    return bool(errors)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('run', type=Path)
    raise SystemExit(analyze(parser.parse_args().run))
