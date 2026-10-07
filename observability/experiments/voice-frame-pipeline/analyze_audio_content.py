"""Review all nonidentical synthetic PCM pairs without replacing the original quality hold."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path

from review_audio_content import cumulative_usage, normalize_text


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main(root):
    directory = root / 'audio-content-review-20261005'
    plan, rows = read(directory / 'plan.json'), read(directory / 'results.json')
    errors, usage, tasks, pairs = [], dict(input_tokens=0, output_tokens=0, total_tokens=0), set(), {}
    if len(rows) != 10 or len(plan['inputs']) != 10:
        errors.append('Expected all ten audio inputs')
    if hashlib.sha256((directory / 'source.py').read_bytes()).hexdigest() != plan['sourceSha256']:
        errors.append('Frozen review source hash mismatch')
    for row, planned in zip(rows, plan['inputs']):
        if any(row[key] != planned[key] for key in planned):
            errors.append('Input plan drift')
        report = read(directory / row['report'])
        if hashlib.sha256(Path(row['source']).read_bytes()).hexdigest() != row['originalSha256']:
            errors.append('Original PCM changed')
        counted = cumulative_usage(report['events'])
        if not counted['finalUsage'] or counted['usageIssues']:
            errors.append(f"Missing/inconsistent cumulative usage: {row['report']}")
        elif counted['finalUsage'] != row['finalUsage']:
            errors.append(f"Returned usage mismatch: {row['report']}")
        else:
            for key in usage:
                usage[key] += counted['finalUsage'][key]
        if report['taskId'] in tasks:
            errors.append('Duplicate ASR task ID')
        tasks.add(report['taskId'])
        if row['status'] != 'success' or normalize_text(row['transcript']) != normalize_text(row['expectedText']):
            errors.append(f"Content mismatch/failed: {row['report']}")
        pair = pairs.setdefault((row['window'], row['pair']), {})
        pair[row['arm']] = row
    for identity, arms in pairs.items():
        if set(arms) != {'whole', 'frames'}:
            errors.append(f'Unpaired ASR content: {identity}')
        elif normalize_text(arms['whole']['transcript']) != normalize_text(arms['frames']['transcript']):
            errors.append(f'A/B transcript differs: {identity}')
    preflight = read(root / 'audio-content-preflight-20261005' / 'usage-correction.json')
    initial = read(root / 'formal-analysis.json')
    hold = 'Nonidentical audio requires content/listening review before equivalence claim'
    other_formal_errors = [error for error in initial['errors'] if error != hold]
    result = dict(reviewedAt=datetime.now(timezone.utc).isoformat(), errors=errors,
                  pairs=len(pairs), audioRequests=len(rows), normalizedExactMatches=sum(row['normalizedExactMatch'] for row in rows),
                  actualReviewUsage=usage, actualPreflightUsage=preflight['finalUsage'],
                  totalAsrUsageIncludingPreflight={key:usage[key]+preflight['finalUsage'][key] for key in usage},
                  usageRule='Use latest complete cumulative event per distinct task; do not sum intermediate usage',
                  otherFormalComponentErrors=other_formal_errors,
                  contentGate=not errors and not other_formal_errors,
                  decision='Retain candidate for actual browser/model A/B; keep default disabled',
                  originalFormalAnalysisUnchanged=True,
                  limitations=['Independent model transcription of synthetic speech; same execution agent checks text',
                               'No human listening, pronunciation/prosody/MOS or physical speaker validation',
                               'Does not satisfy complete browser voice or cancellation performance gates'],
                  evidence=[dict(window=key[0], pair=key[1], transcripts={arm:row['transcript'] for arm,row in arms.items()})
                            for key,arms in pairs.items()])
    target = root / 'audio-content-review.json'
    if target.exists():
        raise ValueError('Preserve existing review')
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({key:value for key,value in result.items() if key != 'evidence'}, ensure_ascii=False, indent=2))
    return bool(errors or other_formal_errors)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('run', type=Path)
    raise SystemExit(main(parser.parse_args().run))
