"""Loopback-only protocol fixture for the actual React voice page.

No model, microphone, credentials or production database is used. All output
must be labelled controlled-browser evidence, never provider latency evidence.
"""
import argparse
import asyncio
import base64
import io
import json
import math
from pathlib import Path
import struct
import time
import wave

from aiohttp import web, WSMsgType


def tone(seconds=0.3):
    samples = [int(700 * math.sin(2 * math.pi * 440 * i / 24000))
               for i in range(int(seconds * 24000))]
    stream = io.BytesIO()
    with wave.open(stream, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(24000)
        wav.writeframes(struct.pack("<" + "h" * len(samples), *samples))
    return base64.b64encode(stream.getvalue()).decode("ascii")


def create_app(output, port):
    events = []
    counter = 0
    audio = tone()
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise ValueError("Use a new evidence output path; existing runs are immutable")

    def record(kind, **values):
        event = {"kind": kind, "evidenceType": "controlled_browser_protocol",
                 "monotonicSeconds": time.monotonic(), **values}
        events.append(event)
        with output.open("a", encoding="utf-8") as target:
            target.write(json.dumps(event, ensure_ascii=False) + "\n")

    def result(data):
        return web.json_response({"code": 200, "message": "success", "data": data})

    def session():
        return {"sessionId": 901, "roleType": "受控语音验收", "currentPhase": "TECH",
                "status": "IN_PROGRESS", "startTime": "2026-10-01T15:00:00",
                "plannedDuration": 30,
                "webSocketUrl": f"ws://127.0.0.1:{port}/ws/voice-interview/901"}

    async def http(request):
        record("http", method=request.method, path=request.path)
        if request.path == "/diagnostics":
            return web.json_response({"evidenceType": "controlled_browser_protocol",
                                      "events": events})
        if request.path == "/api/interview/skills":
            return result([{"id": "java-backend", "name": "Java 后端开发",
                            "description": "受控验收", "categories": [],
                            "isPreset": True, "sourceJd": None}])
        if request.path == "/api/voice-interview/sessions" and request.method == "GET":
            return result([{"sessionId": 901, "roleType": "受控语音验收", "status": "PAUSED",
                            "currentPhase": "TECH", "createdAt": "2026-10-01T15:00:00",
                            "updatedAt": "2026-10-01T15:00:00", "actualDuration": 0,
                            "messageCount": 0, "evaluateStatus": None}])
        if request.path.endswith("/messages"):
            return result([])
        if request.path.endswith("/resume") or request.path == "/api/voice-interview/sessions/901":
            return result(session())
        if request.path == "/api/voice-interview/sessions" and request.method == "POST":
            return result(session())
        if request.path.endswith("/pause") or request.path.endswith("/end"):
            return result(None)
        return result([])

    async def websocket(request):
        nonlocal counter
        ws = web.WebSocketResponse(heartbeat=20)
        await ws.prepare(request)
        tasks = set()
        turn = None
        sequence = 0

        async def send(payload, current_turn=None):
            nonlocal sequence
            sequence += 1
            metadata = {"turnId": current_turn, "eventId": f"fixture-{sequence}",
                        "sequence": sequence, "createdAt": int(time.time() * 1000)}
            if not ws.closed:
                await ws.send_json({**metadata, **payload})
                record("server_event", type=payload["type"], action=payload.get("action"),
                       turnId=current_turn, sequence=sequence)

        async def prepare_answer():
            await send({"type": "subtitle", "text": "这是受控浏览器验收的识别文本。", "isFinal": False})

        async def respond(current_turn, mode):
            await asyncio.sleep(0.15)
            if mode == "cancel":
                return
            if mode == "failure":
                await send({"type": "control", "action": "turn_failed",
                            "message": "受控部分回复失败", "turnPhase": "FAILED"}, current_turn)
                await asyncio.sleep(0.25)
                await send({"type": "audio_chunk", "data": audio, "index": 0,
                            "isLast": True}, current_turn)
                await send({"type": "text", "content": "迟到失败文本，不应展示", "final": True}, current_turn)
            elif mode == "html":
                await send({"type": "audio", "data": audio, "text": "受控完整 WAV 播放。"}, current_turn)
            else:
                await send({"type": "text", "content": "受控 PCM 分片播放。", "final": True}, current_turn)
                await send({"type": "audio_chunk", "data": audio, "index": 0,
                            "isLast": False}, current_turn)
                await send({"type": "audio_chunk", "data": audio, "index": 1,
                            "isLast": True}, current_turn)
                await send({"type": "control", "action": "audio_complete"}, current_turn)
            if mode != "failure":
                await send({"type": "control", "action": "turn_completed",
                            "turnPhase": "COMPLETED"}, current_turn)
            await asyncio.sleep(1)
            await prepare_answer()

        def launch(coroutine):
            task = asyncio.create_task(coroutine)
            tasks.add(task)
            task.add_done_callback(tasks.discard)

        await send({"type": "control", "action": "asr_ready"})
        await prepare_answer()
        try:
            async for message in ws:
                if message.type != WSMsgType.TEXT:
                    continue
                data = json.loads(message.data)
                if data.get("type") != "control":
                    continue
                action = data.get("action")
                control_data = data.get("data") or {}
                record("client_control", **{key: value for key, value in data.items()
                                            if key not in {"text", "data"}},
                       data={key: value for key, value in control_data.items()
                             if key not in {"text", "data"}})
                if action == "submit":
                    counter += 1
                    mode = {1: "pcm", 2: "html", 3: "cancel", 4: "failure"}.get(counter, "pcm")
                    turn = f"fixture-turn-{counter}"
                    record("turn_scenario", mode=mode, turnId=turn)
                    await send({"type": "control", "action": "turn_started",
                                "clientRequestId": control_data.get("clientRequestId"),
                                "turnPhase": "THINKING"}, turn)
                    await send({"type": "text", "content": "受控回复生成中。", "final": False}, turn)
                    launch(respond(turn, mode))
                elif action == "cancel":
                    await send({"type": "control", "action": "turn_cancelled",
                                "cancelRequestId": control_data.get("cancelRequestId"),
                                "turnPhase": "CANCELLED"}, turn)
                    await asyncio.sleep(0.25)
                    await send({"type": "audio_chunk", "data": audio, "index": 0,
                                "isLast": True}, turn)
                    await send({"type": "text", "content": "迟到取消文本，不应展示", "final": True}, turn)
                    await prepare_answer()
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            record("websocket_closed")
        return ws

    app = web.Application()
    app.router.add_get("/ws/voice-interview/901", websocket)
    app.router.add_route("*", "/{tail:.*}", http)
    record("fixture_started", port=port, scenarios=["pcm", "html", "cancel", "failure", "pcm"])
    return app


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18087)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    web.run_app(create_app(args.output, args.port), host="127.0.0.1", port=args.port)
