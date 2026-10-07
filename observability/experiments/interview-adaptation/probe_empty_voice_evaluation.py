"""Real REST creation, empty evaluation via Redis Stream, polling and exact owned cleanup."""
import argparse
import hashlib
import json
import os
import re
import sys
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
sys.path.insert(0, str(Path(__file__).parent))
from probe_training_history_product import data_scope, provider_snapshot
from experimental_index_snapshot import query
from ingest_primary_dev import docker_json

URL = 'http://127.0.0.1:18080'
INPUT = dict(skillId='java-backend', difficulty='mid', introEnabled=False, techEnabled=True,
             projectEnabled=False, hrEnabled=False, plannedDuration=5, llmProvider='dashscope')


def raw(path):
    return Path('\\\\?\\' + str(path.resolve()) if os.name == 'nt' else path).read_bytes()


def save(run, name, value):
    target = run / name
    assert not target.exists()
    target.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def api(method, path, body=None):
    response = requests.request(method, URL + path, json=body, timeout=15)
    response.raise_for_status()
    result = response.json()
    assert result['code'] == 200, result
    return result


def tokens():
    response = requests.get(URL + '/actuator/prometheus', timeout=10)
    response.raise_for_status()
    return '\n'.join(line for line in response.text.splitlines() if line.startswith('gen_ai_client_token_usage_total{'))


def rows(session):
    assert isinstance(session, int) and session > 0
    return query(f"""SELECT json_build_object(
      'sessions',(SELECT count(*) FROM voice_interview_sessions WHERE id={session}),
      'messages',(SELECT count(*) FROM voice_interview_messages WHERE session_id={session}),
      'reports',(SELECT count(*) FROM voice_interview_evaluations WHERE session_id={session}),
      'status',(SELECT evaluate_status FROM voice_interview_sessions WHERE id={session}));""")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    parser.add_argument('--source-run', required=True)
    parser.add_argument('--expect', choices=('stale', 'complete'), required=True)
    args = parser.parse_args()
    run = (BASE / args.output).resolve()
    source = (BASE / args.source_run).resolve()
    assert run.is_relative_to(BASE) and source.is_relative_to(BASE)
    run.mkdir()
    save(run, 'input.json', dict(create=INPUT, sequence=['POST create', 'POST evaluation', 'wait DB', 'GET evaluation', 'DELETE'],
        externalModelCalls=0, noWebSocket=True, emptyConversation=True, noAsrOrTts=True, realResumeOrJd=False))
    save(run, 'scope-before.json', data_scope())
    save(run, 'provider-before.json', provider_snapshot())
    manifest = json.loads((source / 'source-manifest.json').read_text(encoding='utf-8'))
    (run / 'sources').mkdir()
    for item in manifest['files']:
        contents = raw(source / item['frozen'])
        assert hashlib.sha256(contents).hexdigest() == item['sha256']
        (run / item['frozen']).write_bytes(contents)
    contents = raw(Path(__file__))
    frozen = 'sources/probe_empty_voice_evaluation.py'
    (run / frozen).write_bytes(contents)
    manifest['files'].append(dict(path=Path(__file__).relative_to(ROOT).as_posix(),
                                 frozen=frozen, sha256=hashlib.sha256(contents).hexdigest()))
    save(run, 'source-manifest.json', manifest)
    save(run, 'runtime-source-binding.json', dict(sourceRun=args.source_run,
        oldRuntimeStartedBeforeSourceChange=args.expect == 'stale', expected=args.expect))
    before_tokens = tokens()
    (run / 'metrics-before.txt').write_text(before_tokens, encoding='utf-8')
    session = None
    terminal = False
    observations = []
    try:
        created = api('POST', '/api/voice-interview/sessions', INPUT)
        save(run, 'create-response.json', created)
        session = int(created['data']['sessionId'])
        save(run, 'db-before.json', rows(session))
        assert rows(session)['messages'] == rows(session)['reports'] == 0
        triggered = api('POST', f'/api/voice-interview/sessions/{session}/evaluation')
        save(run, 'trigger-response.json', triggered)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            state = rows(session)
            observations.append(state)
            if state['status'] in ('COMPLETED', 'FAILED'):
                terminal = True
                break
            time.sleep(0.25)
        save(run, 'db-polls.json', observations)
        assert terminal and state['status'] == 'COMPLETED' and state['reports'] == 1
        response = api('GET', f'/api/voice-interview/sessions/{session}/evaluation')
        save(run, 'poll-response.json', response)
        visible = response['data']['evaluateStatus'] == 'COMPLETED' and response['data'].get('evaluation') is not None
        result = dict(ownedSessionId=session, db=state, completedReportVisible=visible,
                      actualCreateEndpoint=True, actualStreamConsumer=True, externalModelCalls=0,
                      emptyConversation=True, realResumeOrJd=False, expected=args.expect)
        save(run, 'result.json', result)
        assert visible == (args.expect == 'complete')
    finally:
        if session is not None:
            assert terminal, 'Do not delete while the owned evaluation is still running; retain evidence.'
            removed = api('DELETE', f'/api/voice-interview/sessions/{session}')
            save(run, 'delete-response.json', removed)
            after = rows(session)
            key = 'voice:interview:session:' + str(session)
            exists = docker_json('redis', 'redis-cli', '--json', 'EXISTS', key)
            assert after == dict(sessions=0, messages=0, reports=0, status=None) and exists == 0
            save(run, 'owned-cleanup-proof.json', dict(sessionId=session, db=after, exactCacheKey=key, cacheExists=exists))
        save(run, 'scope-after.json', data_scope())
        after_tokens = tokens()
        (run / 'metrics-after.txt').write_text(after_tokens, encoding='utf-8')
        assert before_tokens == after_tokens
    print(json.dumps(result))


if __name__ == '__main__':
    main()
