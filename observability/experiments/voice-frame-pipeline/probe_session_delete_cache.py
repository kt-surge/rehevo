"""Use only our own REST session to compare DB/cache state after deletion."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import sys
from datetime import datetime, timezone

import requests

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "observability/experiments/rag-evaluation"))
from experimental_index_snapshot import current, assert_scope, vectors_hash, query
from ingest_primary_dev import docker_json, provider_snapshot

BASE_URL = "http://127.0.0.1:18080"
PUBLIC_SHA = "76d903d460a4f34c5f3fedcc1e31b998cb999a6fb8d2ad6105760c1ed30b495b"


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def api(method, path, **kwargs):
    response = requests.request(method, BASE_URL + path, timeout=15, **kwargs)
    response.raise_for_status()
    return response.json()


def scope():
    state = current()
    assert_scope(state)
    sha = vectors_hash(state)
    assert sha == PUBLIC_SHA
    counts = query("""SELECT json_build_object('documents',(SELECT count(*) FROM knowledge_bases),
      'vectors',(SELECT count(*) FROM vector_store),'voiceSessions',(SELECT count(*) FROM voice_interview_sessions),
      'ragSessions',(SELECT count(*) FROM rag_chat_sessions),'ragMessages',(SELECT count(*) FROM rag_chat_messages));""")
    assert counts == dict(documents=15, vectors=123, voiceSessions=0, ragSessions=0, ragMessages=0), counts
    return dict(publicVectorSha256=sha, counts=counts)


def owned_db_rows(session_id):
    assert isinstance(session_id, int) and session_id > 0
    return query(f"SELECT json_build_object('sessions',(SELECT count(*) FROM voice_interview_sessions WHERE id={session_id}),'messages',(SELECT count(*) FROM voice_interview_messages WHERE session_id={session_id}),'evaluations',(SELECT count(*) FROM voice_interview_evaluations WHERE session_id={session_id}));")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--expect", choices=("stale", "clear"), required=True)
    args = parser.parse_args()
    run = args.output.resolve()
    assert run.is_relative_to(ROOT / "observability/experiments/voice-frame-pipeline/runs")
    run.mkdir(exist_ok=False)
    save(run / "scope-before.json", scope())
    save(run / "provider-before.json", provider_snapshot())
    request = dict(skillId="java-backend", difficulty="mid", introEnabled=False,
                   techEnabled=True, projectEnabled=False, hrEnabled=False, plannedDuration=5)
    save(run / "plan.json", dict(createdAt=datetime.now(timezone.utc).isoformat(),
        request=request, expected=args.expect, maxRestSessions=1, websocketOpened=False,
        submitOrEvaluationCalled=False, modelCallsPlanned=0, realResumeOrJdIncluded=False))
    source = run / "sources"
    source.mkdir()
    files = []
    for index, name in enumerate((
        "app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewService.java",
        "app/src/main/java/interview/guide/modules/voiceinterview/controller/VoiceInterviewController.java",
        "observability/experiments/voice-frame-pipeline/probe_session_delete_cache.py",
        "observability/experiments/voice-frame-pipeline/SESSION_DELETE_CACHE_DESIGN_2026-10-05.md",
    )):
        dest = source / f"{index:03d}-{Path(name).name}"
        shutil.copyfile(ROOT / name, dest)
        files.append(dict(path=name, frozen=dest.relative_to(run).as_posix(),
                          sha256=hashlib.sha256(dest.read_bytes()).hexdigest()))
    save(run / "source-manifest.json", dict(files=files))
    before_metrics = requests.get(BASE_URL + "/actuator/prometheus", timeout=10).text
    (run / "metrics-before.txt").write_text("\n".join(line for line in before_metrics.splitlines()
        if line.startswith("gen_ai_client_token_usage_total{")), encoding="utf-8")
    session_id = None
    try:
        created = api("POST", "/api/voice-interview/sessions", json=request)
        save(run / "created-session.json", created)
        assert created["code"] == 200
        session_id = int(created["data"]["sessionId"])
        key = f"voice:interview:session:{session_id}"
        before_cache = docker_json("redis", "redis-cli", "--json", "EXISTS", key)
        before_db = owned_db_rows(session_id)
        assert before_cache == 1 and before_db["sessions"] == 1
        delete_response = api("DELETE", f"/api/voice-interview/sessions/{session_id}")
        save(run / "delete-response.json", delete_response)
        assert delete_response["code"] == 200
        after_cache = docker_json("redis", "redis-cli", "--json", "EXISTS", key)
        after_db = owned_db_rows(session_id)
        get_response = api("GET", f"/api/voice-interview/sessions/{session_id}")
        save(run / "get-after-delete.json", get_response)
        result = dict(ownedSessionId=session_id, beforeCacheExists=before_cache,
            afterCacheExists=after_cache, beforeDb=before_db, afterDb=after_db,
            deletedSessionStillReadable=get_response["code"] == 200 and get_response.get("data") is not None,
            websocketOpened=False, modelRequests=0, expected=args.expect)
        save(run / "result.json", result)
        assert all(count == 0 for count in after_db.values()), after_db
        if args.expect == "stale":
            assert after_cache == 1 and result["deletedSessionStillReadable"]
        else:
            assert after_cache == 0 and not result["deletedSessionStillReadable"]
    finally:
        if session_id is not None:
            # Only this response-created ID/key; never enumerate/delete unrelated entries.
            db = owned_db_rows(session_id)
            if db["sessions"]:
                cleanup = api("DELETE", f"/api/voice-interview/sessions/{session_id}")
                save(run / "cleanup-response.json", cleanup)
                assert cleanup["code"] == 200
            assert all(value == 0 for value in owned_db_rows(session_id).values())
            key = f"voice:interview:session:{session_id}"
            exists = docker_json("redis", "redis-cli", "--json", "EXISTS", key)
            removed = docker_json("redis", "redis-cli", "--json", "DEL", key) if exists else 0
            assert docker_json("redis", "redis-cli", "--json", "EXISTS", key) == 0
            save(run / "owned-cleanup-proof.json", dict(ownedSessionId=session_id,
                 exactKey=key, cacheExistsBeforeCleanup=exists, cacheKeysRemoved=removed))
        save(run / "scope-after.json", scope())
        after_metrics = requests.get(BASE_URL + "/actuator/prometheus", timeout=10).text
        tokens = "\n".join(line for line in after_metrics.splitlines()
                          if line.startswith("gen_ai_client_token_usage_total{"))
        (run / "metrics-after.txt").write_text(tokens, encoding="utf-8")
        assert tokens == (run / "metrics-before.txt").read_text(encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
