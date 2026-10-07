"""One real voice answer/evaluation, then a text retest creation; no cross-model study."""
import argparse
import asyncio
import hashlib
import json
from pathlib import Path
import sys
import time

from aiohttp import ClientSession, ClientTimeout

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "observability/experiments/voice-frame-pipeline"))
sys.path.insert(0, str(ROOT / "observability/experiments/rag-evaluation"))
from real_question_quality_probe import Probe
from experimental_index_snapshot import current, assert_scope, vectors_hash, query
from ingest_primary_dev import provider_snapshot, docker_json


def save(run, name, value):
    (run / name).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def data_scope():
    state = current()
    assert_scope(state)
    assert vectors_hash(state) == "76d903d460a4f34c5f3fedcc1e31b998cb999a6fb8d2ad6105760c1ed30b495b"
    counts = query("""SELECT json_build_object('documents',(SELECT count(*) FROM knowledge_bases),
      'vectors',(SELECT count(*) FROM vector_store),'voiceSessions',(SELECT count(*) FROM voice_interview_sessions),
      'textSessions',(SELECT count(*) FROM interview_sessions),'ragSessions',(SELECT count(*) FROM rag_chat_sessions),
      'ragMessages',(SELECT count(*) FROM rag_chat_messages));""")
    assert counts == dict(documents=15, vectors=123, voiceSessions=0,
                          textSessions=0, ragSessions=0, ragMessages=0), counts
    return dict(counts=counts, publicVectorSha256=vectors_hash(state))


async def main(args):
    scope = data_scope()
    providers = provider_snapshot()
    defaults = providers["defaultProviders"]
    models = [p["model"] for p in providers["providers"] if p.get("defaultChatProvider")]
    assert models == ["qwen3.8-flash"], models
    probe = Probe(args.output, args.inputs, candidate_only=True)
    run = probe.output
    save(run, "scope-before.json", scope)
    save(run, "provider-before.json", providers)
    save(run, "product-plan.json", dict(maxVoiceSubmissions=1, maxEvaluationEnqueues=1,
        maxTextCreates=1, model="qwen3.8-flash", realResumeOrJd=False,
        websocketInput="controlled public text", noAsrAudio=True,
        notBrowserTiming=True, internalProviderRequestCount="observe-not-assume"))
    source_dir = run / "sources"
    source_dir.mkdir()
    files = [ROOT / name for name in (
        "app/src/main/java/interview/guide/modules/interview/service/InterviewPersistenceService.java",
        "app/src/main/java/interview/guide/modules/interview/service/InterviewSessionService.java",
        "app/src/main/java/interview/guide/modules/interview/service/InterviewQuestionService.java",
        "app/src/main/java/interview/guide/modules/interview/service/InterviewPlanService.java",
        "app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceInterviewEvaluationService.java",
        "app/src/main/java/interview/guide/common/evaluation/UnifiedEvaluationService.java",
        "app/src/main/java/interview/guide/common/ai/StructuredOutputInvoker.java",
        "app/src/main/resources/application.yml",
        "observability/experiments/interview-adaptation/TRAINING_HISTORY_PRODUCT_PREFLIGHT_2026-10-05.md",
        "observability/experiments/interview-adaptation/probe_training_history_product.py",
        "observability/experiments/voice-frame-pipeline/real_question_quality_probe.py",
    )]
    files += [p for p in (ROOT / "app/src/main/resources/prompts").rglob("*.st") if p.is_file()]
    files += list((ROOT / "app/src/main/java/interview/guide/modules/voiceinterview").rglob("*.java"))
    files = sorted(set(files))
    frozen = []
    for index, file in enumerate(files):
        dest = source_dir / f"{index:03d}-{file.name}"
        dest.write_bytes(file.read_bytes())
        frozen.append(dict(path=file.relative_to(ROOT).as_posix(),
                           frozen=dest.relative_to(run).as_posix(),
                           sha256=hashlib.sha256(dest.read_bytes()).hexdigest()))
    save(run, "source-manifest.json", dict(files=frozen))
    voice_id = None
    text_id = None
    ws = None
    terminal_evaluation = False
    evaluation_enqueued = False
    result = None
    async with ClientSession(timeout=ClientTimeout(total=70, connect=10)) as http:
        probe.http = http
        try:
            voice_id, ws = await probe.create("candidate", "public-voice-to-text")
            await probe.metrics("candidate", "voice-answer-before")
            text = probe.plan["sequences"][0]["texts"][0]
            event = dict(type="control", action="submit", data=dict(
                text=text, clientRequestId="training-history-controlled-1"))
            probe.record("submit", sessionId=voice_id, event=event)
            await ws.send_json(event)
            await probe.receive(ws, "candidate", voice_id, "public-voice-answer", time.monotonic())
            await probe.metrics("candidate", "voice-answer-after")
            await ws.close()
            async with http.get(f"http://127.0.0.1:18080/api/voice-interview/sessions/{voice_id}/messages") as r:
                history = await r.json()
            save(run, "voice-history.json", history)
            evaluation_enqueued = True  # On an uncertain HTTP result, preserve until authoritative terminal.
            async with http.post(f"http://127.0.0.1:18080/api/voice-interview/sessions/{voice_id}/end") as r:
                ended = await r.json()
            save(run, "voice-end.json", ended)
            assert ended["code"] == 200
            evaluation = None
            deadline = asyncio.get_running_loop().time() + 120
            while asyncio.get_running_loop().time() < deadline:
                async with http.get(f"http://127.0.0.1:18080/api/voice-interview/sessions/{voice_id}/evaluation") as r:
                    status = await r.json()
                probe.record("evaluation_status", sessionId=voice_id, response=status)
                assert status["code"] == 200
                state = status["data"]["evaluateStatus"]
                if state in ("COMPLETED", "FAILED"):
                    terminal_evaluation = True
                    save(run, "voice-evaluation.json", status)
                    assert state == "COMPLETED", status
                    evaluation = status["data"]["evaluation"]
                    break
                await asyncio.sleep(2)
            assert evaluation is not None, "Evaluation still in progress; preserve owned session for explicit recovery"
            await probe.metrics("candidate", "voice-evaluation-after")
            tasks = evaluation.get("trainingTasks") or []
            assert evaluation.get("scoredQuestions", 0) > 0 and tasks, "No usable scored training task"
            request = dict(resumeText=None, questionCount=3, resumeId=None, forceCreate=True,
                llmProvider=None, skillId="java-backend", difficulty="junior",
                customCategories=None, jdText=None)
            save(run, "text-create-request.json", request)
            async with http.post("http://127.0.0.1:18080/api/interview/sessions", json=request) as r:
                created = await r.json()
            save(run, "text-session.json", created)
            assert created["code"] == 200, created
            text_id = created["data"]["sessionId"]
            await probe.metrics("candidate", "text-create-after")
            plan = created["data"]["plan"]
            result = dict(voiceSessionId=voice_id, textSessionId=text_id,
                scoredVoiceQuestions=evaluation["scoredQuestions"],
                trainingTasks=tasks, textPlan=plan,
                tasksReceived=plan["requestedFocusCompetencies"] > 0,
                anyGeneratedQuestionPrioritized=plan["prioritizedCompetencies"] > 0,
                fullProductFlow=False, crossModelComparisons=0,
                asrUsage="unknown-no-audio-upload", ttsUsage="unknown-not-exposed-by-default-app")
            save(run, "result.json", result)
            assert result["tasksReceived"], "Actual text prep did not receive voice training tasks"
        except Exception as error:
            probe.record("product_preflight_failed", errorType=type(error).__name__, message=str(error))
            raise
        finally:
            if ws is not None:
                await ws.close()
            if voice_id is None and probe.owned:
                voice_id = probe.owned[0][1]
            if text_id is not None:
                async with http.delete(f"http://127.0.0.1:18080/api/interview/sessions/{text_id}") as r:
                    cleanup = await r.json()
                probe.record("text_cleanup", sessionId=text_id, response=cleanup)
                assert cleanup["code"] == 200
                # Current text DELETE leaves Redis; only remove this response-created exact key.
                removed = docker_json("redis", "redis-cli", "--json", "DEL", f"interview:session:{text_id}")
                probe.record("owned_text_cache_cleanup", sessionId=text_id, removed=removed)
            if voice_id is not None:
                if not terminal_evaluation:
                    state = query(f"SELECT json_build_object('status',evaluate_status) FROM voice_interview_sessions WHERE id={int(voice_id)};")
                    terminal_evaluation = state["status"] in ("COMPLETED", "FAILED")
                if terminal_evaluation or not evaluation_enqueued:
                    async with http.delete(f"http://127.0.0.1:18080/api/voice-interview/sessions/{voice_id}") as r:
                        cleanup = await r.json()
                    probe.record("voice_cleanup", sessionId=voice_id, response=cleanup)
                    assert cleanup["code"] == 200
                else:
                    probe.record("owned_session_kept_until_evaluation_terminal", sessionId=voice_id)
            await probe.metrics("candidate", "probe-final")
            if voice_id is None or terminal_evaluation or not evaluation_enqueued:
                save(run, "scope-after.json", data_scope())
    print(json.dumps({k: v for k, v in result.items() if k not in ("trainingTasks", "textPlan")}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--inputs", type=Path, required=True)
    asyncio.run(main(parser.parse_args()))
