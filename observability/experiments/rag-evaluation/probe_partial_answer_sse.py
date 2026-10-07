"""One public controlled application SSE probe; no user session/documents are created."""
from datetime import datetime, timezone
import json
from pathlib import Path
import time

import requests


RUN = Path(__file__).resolve().parent / 'runs/generation-boundary-20261005-r1'
BASE = 'http://127.0.0.1:18080'
REQUEST = dict(knowledgeBaseIds=[3], question='请用两句回答：根据 XREADGROUP 公开文档，NOACK 是否把消息放进 PEL？文档是否给出我们生产任务重试的具体秒数？未提供依据的部分明确写信息不足。')


def meters():
    text = requests.get(BASE + '/actuator/prometheus', timeout=10).text
    return '\n'.join(line for line in text.splitlines() if line.startswith('gen_ai_client_token_usage_total{')) + '\n'


def main():
    out = RUN / 'real-sse-probe'
    out.mkdir(exist_ok=False)
    (out / 'request.json').write_text(json.dumps(REQUEST, ensure_ascii=False, indent=2), encoding='utf-8')
    (out / 'metrics-before.txt').write_text(meters(), encoding='utf-8')
    chunks = []
    started = time.perf_counter()
    data_lines = []
    with (out / 'raw-sse-lines.jsonl').open('x', encoding='utf-8') as raw:
        with requests.post(BASE + '/api/knowledgebase/query/stream', json=REQUEST, stream=True, timeout=(10, 75)) as response:
            status = response.status_code
            for line in response.iter_lines(chunk_size=1):
                elapsed = (time.perf_counter() - started) * 1000
                text = line.decode('utf-8')
                raw.write(json.dumps(dict(at=datetime.now(timezone.utc).isoformat(), elapsedMs=elapsed, line=text), ensure_ascii=False) + '\n')
                raw.flush()
                if text.startswith('data:'):
                    value = text[5:]
                    if value.startswith(' '):
                        value = value[1:]
                    data_lines.append(value)
                elif not text and data_lines:
                    chunks.append(dict(elapsedMs=elapsed, text='\n'.join(data_lines)))
                    data_lines = []
            if data_lines:
                chunks.append(dict(elapsedMs=(time.perf_counter() - started) * 1000, text='\n'.join(data_lines)))
    ended_ms = (time.perf_counter() - started) * 1000
    (out / 'metrics-after.txt').write_text(meters(), encoding='utf-8')
    answer = ''.join(chunk['text'] for chunk in chunks)
    result = dict(scope='one actual stateless HTTP SSE product endpoint, not browser or paired performance',
                  httpStatus=status, chunks=chunks, fullAnswer=answer, characters=len(answer),
                  firstNonblankMs=next((c['elapsedMs'] for c in chunks if c['text'].strip()), None),
                  endedMs=ended_ms,
                  partialFactsAndMissingNoticePreserved=('NOACK' in answer and 'PEL' in answer and '信息不足' in answer),
                  upstreamFailureInOutput=('【错误】' in answer), createdSessions=0)
    (out / 'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
