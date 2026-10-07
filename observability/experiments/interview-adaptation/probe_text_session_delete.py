"""Own one SQL-seeded public fixture; actual GET/DELETE/GET and DB/Redis observations."""
import argparse
import hashlib
import json
import os
import re
import sys
import uuid
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(Path(__file__).parent))
from probe_training_history_product import data_scope, provider_snapshot
from experimental_index_snapshot import query
from ingest_primary_dev import docker_json

PUBLIC_QUESTION = dict(questionIndex=0, question='Redis Stream 消费者提交数据库后、ACK 前崩溃，如何避免重复写入？',
    type='REDIS', category='Redis', topicSummary='提交与确认', userAnswer=None,
    score=None, feedback=None, isFollowUp=False, parentQuestionIndex=None, evaluationGuide=None)
BASE_URL = 'http://127.0.0.1:18080'


def read(path):
    return Path('\\\\?\\' + str(path.resolve())).read_bytes() if os.name == 'nt' else path.read_bytes()


def save(run, name, value):
    assert not (run / name).exists()
    (run / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def api(method, path):
    response = requests.request(method, BASE_URL + path, timeout=20)
    response.raise_for_status()
    return response.json()


def rows(session_id):
    assert re.fullmatch('[a-f0-9]{16}', session_id)
    return query(f"""SELECT json_build_object('sessions',(SELECT count(*) FROM interview_sessions WHERE session_id='{session_id}'),
      'answers',(SELECT count(*) FROM interview_answers a JOIN interview_sessions s ON s.id=a.session_id WHERE s.session_id='{session_id}'));""")


def tokens():
    response = requests.get(BASE_URL + '/actuator/prometheus', timeout=10)
    response.raise_for_status()
    return '\n'.join(line for line in response.text.splitlines() if line.startswith('gen_ai_client_token_usage_total{'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--expect', choices=('stale', 'clear'), required=True)
    args = parser.parse_args()
    run = args.output.resolve()
    assert run.is_relative_to(ROOT / 'observability/experiments/voice-frame-pipeline/runs')
    run.mkdir()
    save(run, 'scope-before.json', data_scope())
    save(run, 'provider-before.json', provider_snapshot())
    save(run, 'input.json', dict(question=PUBLIC_QUESTION, source='controlled SQL seed', modelCalls=0,
        actualCreateEndpointTest=False, sequence=['GET restores cache', 'DELETE', 'GET after deletion']))
    source = run / 'sources'
    source.mkdir()
    manifest = []
    for i, name in enumerate((
        'app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java',
        'app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java',
        'app/src/main/java/interview/guide/modules/interview/InterviewController.java',
        'app/src/main/java/interview/guide/infrastructure/redis/InterviewSessionCache.java',
        'observability/experiments/interview-adaptation/TEXT_DELETE_CACHE_DESIGN_2026-10-05.md',
        'observability/experiments/interview-adaptation/probe_text_session_delete.py')):
        raw = read(ROOT / name)
        frozen = f'sources/{i:03d}-{Path(name).name}'
        (run / frozen).write_bytes(raw)
        manifest.append(dict(path=name, frozen=frozen, sha256=hashlib.sha256(raw).hexdigest()))
    save(run, 'source-manifest.json', dict(files=manifest))
    before_tokens = tokens()
    (run / 'metrics-before.txt').write_text(before_tokens, encoding='utf-8')
    session_id = uuid.uuid4().hex[:16]
    key = 'interview:session:' + session_id
    seeded = False
    result = None
    try:
        question_json = json.dumps([PUBLIC_QUESTION]).replace("'", "''")
        seed = query(f"""WITH inserted AS (INSERT INTO interview_sessions
          (session_id,skill_id,difficulty,total_questions,current_question_index,status,questions_json,created_at,llm_provider)
          VALUES ('{session_id}','java-backend','mid',1,0,'CREATED','{question_json}',CURRENT_TIMESTAMP,'dashscope')
          RETURNING id,session_id) SELECT row_to_json(inserted) FROM inserted;""")
        seeded = True
        assert seed['session_id'] == session_id
        save(run, 'owned-seed.json', seed)
        before = api('GET', f'/api/interview/sessions/{session_id}')
        save(run, 'get-before.json', before)
        assert before['code'] == 200
        before_db, before_cache = rows(session_id), docker_json('redis', 'redis-cli', '--json', 'EXISTS', key)
        assert before_db == dict(sessions=1, answers=0) and before_cache == 1
        deleted = api('DELETE', f'/api/interview/sessions/{session_id}')
        save(run, 'delete-response.json', deleted)
        assert deleted['code'] == 200
        after_db, after_cache = rows(session_id), docker_json('redis', 'redis-cli', '--json', 'EXISTS', key)
        after = api('GET', f'/api/interview/sessions/{session_id}')
        save(run, 'get-after.json', after)
        readable = after['code'] == 200 and after.get('data') is not None
        result = dict(ownedSessionId=session_id, beforeDb=before_db, afterDb=after_db,
            beforeCacheExists=before_cache, afterCacheExists=after_cache, deletedSessionStillReadable=readable,
            modelCalls=0, realResumeOrJd=False, expected=args.expect)
        save(run, 'result.json', result)
        assert after_db == dict(sessions=0, answers=0)
        assert (after_cache == 1 and readable) if args.expect == 'stale' else (after_cache == 0 and not readable)
    finally:
        if seeded:
            remaining = rows(session_id)
            if remaining['sessions']:
                assert api('DELETE', f'/api/interview/sessions/{session_id}')['code'] == 200
            assert rows(session_id) == dict(sessions=0, answers=0)
            exists = docker_json('redis', 'redis-cli', '--json', 'EXISTS', key)
            removed = docker_json('redis', 'redis-cli', '--json', 'DEL', key) if exists else 0
            assert docker_json('redis', 'redis-cli', '--json', 'EXISTS', key) == 0
            save(run, 'owned-cleanup-proof.json', dict(sessionId=session_id, exactKey=key,
                existedBeforeCleanup=exists, removed=removed))
        save(run, 'scope-after.json', data_scope())
        after_tokens = tokens()
        (run / 'metrics-after.txt').write_text(after_tokens, encoding='utf-8')
        assert before_tokens == after_tokens
    print(json.dumps(result))


if __name__ == '__main__':
    main()
