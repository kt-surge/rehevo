"""Bounded newly authored public synthetic benchmark; no application prompt extraction."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import time

import requests


ROOT = next(parent for parent in Path(__file__).resolve().parents
            if (parent / 'gradlew.bat').is_file() and (parent / 'app').is_dir())
ENDPOINT = 'https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions'
ALLOWED_MODELS = {'qwen3.8-flash', 'qwen3.8-27b', 'qwen3.8-max-0902'}


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def write(path, value):
    with path.open('x', encoding='utf-8') as file:
        json.dump(value, file, ensure_ascii=False, indent=2)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('plan', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--offset', type=int, default=0)
    parser.add_argument('--limit', type=int, default=3)
    args = parser.parse_args()
    plan = json.loads(args.plan.read_text(encoding='utf-8'))
    if plan.get('payloadOrigin') != 'public-synthetic-v1':
        raise ValueError('Only newly authored public synthetic fixtures are allowed; no application prompt replay')
    allowed = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
    output = args.output.resolve()
    if not output.is_relative_to(allowed) or any((p / 'artifacts.sha256.json').exists()
                                               for p in output.parents if p.is_relative_to(allowed)):
        raise ValueError('Use a new unsealed experiment output')
    if args.limit < 1 or args.limit > 12 or args.offset < 0 or args.offset + args.limit > len(plan['schedule']):
        raise ValueError('Invalid bounded call slice')
    key = os.environ.get('REHEVO_TTS_EXPERIMENT_API_KEY')
    if not key:
        raise ValueError('Existing authorized credential is unavailable; no calls made')
    if len(plan['schedule']) > 24 or any(call['model'] not in ALLOWED_MODELS for call in plan['schedule']):
        raise ValueError('Only listed screenshot models and at most 24 prepared requests are allowed')
    output.mkdir(exist_ok=False)
    write(output / 'plan-capture.json', dict(planSha256=digest(args.plan.read_bytes()), offset=args.offset, limit=args.limit,
                                           scope='public synthetic draft component probe, not product/browser latency'))
    traces = output / 'events.jsonl'
    with traces.open('x', encoding='utf-8') as events, requests.Session() as http:
        def event(kind, data):
            row = dict(kind=kind, at=datetime.now(timezone.utc).isoformat(), **data)
            text = json.dumps(row, ensure_ascii=False)
            if key in text:
                text = text.replace(key, '[redacted-existing-credential]')
            events.write(text + '\n')
            events.flush()

        for index, call in enumerate(plan['schedule'][args.offset:args.offset + args.limit], start=args.offset):
            fixture = plan['cases'][call['caseId']]
            request = dict(model=call['model'], messages=fixture['messages'],
                           temperature=plan['temperature'], max_completion_tokens=480,
                           enable_thinking=False, preserve_thinking=False,
                           stream=True, stream_options=dict(include_usage=True))
            request_raw = json.dumps(request, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
            write(output / f'{index:02d}-request.json', request)
            identity = dict(index=index, caseId=call['caseId'], model=call['model'])
            event('begin', dict(trace=identity, payloadSha256=digest(request_raw)))
            started = time.perf_counter()
            chunks, content, usage, first_ms, question_ms, finish = [], '', None, None, None, None
            successful, failure = False, None
            try:
                with http.post(ENDPOINT, headers={'Authorization': 'Bearer ' + key,
                                                   'Content-Type': 'application/json'},
                               data=request_raw, stream=True, timeout=(10, 10)) as response:
                    event('http_status', dict(trace=identity, status=response.status_code))
                    if response.status_code != 200:
                        failure = dict(kind='http_error', status=response.status_code)
                        event('http_failure_body', dict(trace=identity, body=response.text[:8000]))
                    else:
                        for line in response.iter_lines(chunk_size=1):
                            elapsed = (time.perf_counter() - started) * 1000
                            if elapsed > 20000:
                                failure = dict(kind='absolute_deadline', elapsedMs=elapsed)
                                break
                            if not line or not line.startswith(b'data:'):
                                continue
                            value = line[5:].strip()
                            if value == b'[DONE]':
                                successful = usage is not None and finish == 'stop' and bool(content)
                                break
                            chunk = json.loads(value)
                            chunks.append(chunk)
                            event('sse', dict(trace=identity, elapsedMs=elapsed, data=chunk))
                            if chunk.get('usage'):
                                usage = chunk['usage']
                            if chunk.get('error'):
                                failure = dict(kind='sse_error', error=chunk['error'])
                            for choice in chunk.get('choices', []):
                                text = choice.get('delta', {}).get('content') or ''
                                if text and first_ms is None:
                                    first_ms = elapsed
                                content += text
                                if question_ms is None and ('？' in content or '?' in content):
                                    question_ms = elapsed
                                if choice.get('finish_reason'):
                                    finish = choice['finish_reason']
            except (requests.RequestException, ValueError) as error:
                # Do not serialize the prepared request object/headers or sensitive exceptions.
                failure = dict(kind='client_error', exceptionType=type(error).__name__)
            result = dict(trace=identity, success=successful, content=content, finalUsage=usage,
                          usageStatus='observed_vendor_final_usage' if usage is not None else 'unknown',
                          firstContentMs=first_ms, firstQuestionMarkMs=question_ms,
                          elapsedMs=(time.perf_counter() - started) * 1000, finishReason=finish,
                          failure=failure, sseEvents=len(chunks),
                          questionWithinBudget=bool(content) and len(content) <= 120 and content.endswith(('？', '?')))
            event('result', result)
            print(json.dumps(result, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
