"""Summarize frozen application ASR diagnostics without making provider requests."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
from datetime import datetime, timezone
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
BASE = Path(__file__).resolve().parent
RUNS = BASE / "runs"


def read(file):
    path = str(file.resolve())
    return Path("\\\\?\\" + path if os.name == "nt" else path).read_bytes()


def save(file, value):
    file.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def copy(file, destination):
    path = str(destination.resolve())
    Path("\\\\?\\" + path if os.name == "nt" else path).write_bytes(read(file))


def summarize_tests():
    run = RUNS / "application-asr-identity-20261005-r2"
    assert not (run / "artifacts.sha256.json").exists()
    xmls = list((ROOT / "app/build/test-results/test").glob("TEST-*.xml"))
    summary = dict(suites=len(xmls), tests=0, failures=0, errors=0, skipped=0)
    for file in xmls:
        tree = ET.fromstring(read(file))
        for key in ("tests", "failures", "errors", "skipped"):
            summary[key] += int(tree.get(key, 0))
        copy(file, run / file.name)
    assert summary == dict(suites=82, tests=409, failures=0, errors=0, skipped=0), summary
    save(run / "candidate-full-regression-summary.json", summary)
    names = [
        "app/src/main/java/interview/guide/modules/voiceinterview/service/QwenAsrService.java",
        "app/src/main/java/interview/guide/modules/voiceinterview/service/VoiceAsrSegmentConsumer.java",
        "app/src/main/java/interview/guide/modules/voiceinterview/turn/AsrTranscriptSegment.java",
        "app/src/main/java/interview/guide/modules/voiceinterview/turn/AsrFinalSegmentBuffer.java",
        "app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java",
        "app/src/test/java/interview/guide/modules/voiceinterview/service/QwenAsrSegmentationTest.java",
        "app/src/test/java/interview/guide/modules/voiceinterview/service/QwenAsrSegmentIdentityTest.java",
        "app/src/test/java/interview/guide/modules/voiceinterview/turn/AsrFinalSegmentBufferTest.java",
        "app/src/main/resources/application.yml", "observability/experiments/runtime/boot-run.ps1",
        "observability/experiments/voice-frame-pipeline/probe_application_asr.py",
        "observability/experiments/voice-frame-pipeline/finalize_application_asr.py",
    ]
    sources = run / "sources"
    sources.mkdir(exist_ok=True)
    manifest = {"files": []}
    for index, name in enumerate(names):
        frozen = sources / f"{index:03d}-{Path(name).name}"
        frozen.write_bytes(read(ROOT / name))
        manifest["files"].append(dict(path=name, frozen=frozen.relative_to(run).as_posix(),
                                      sha256=hashlib.sha256(read(frozen)).hexdigest()))
    save(run / "source-manifest.json", manifest)
    save(run / "validation-error-followup.json", {
        "reason": "Preserve BusinessException as required by repository rules",
        "prior409TestRunPreserved": True, "additionalProviderCalls": 0,
        "successfulRecognitionPathChanged": False,
        "onErrorReceivesSameBusinessException": True,
    })
    return summary


def observations():
    rows = []
    for index in (2, 3, 5, 6):
        run = RUNS / f"application-asr-20261005-r{index}"
        result = json.loads((run / "result.json").read_text(encoding="utf-8"))
        log = (run / "application-log-excerpt.txt").read_text(encoding="utf-8")
        final_lines = [line for line in log.splitlines() if "STT final segment for session" in line]
        connections = log.count("Transcription session started successfully")
        first_final_ms = None
        if final_lines:
            final_at = datetime.fromisoformat(final_lines[0].split()[0])
            last_sent_at = datetime.fromisoformat(result["lastSpeechFrameSendAt"])
            first_final_ms = round((final_at - last_sent_at).total_seconds() * 1000, 3)
        before = (run / "metrics-before.txt").read_text()
        after = (run / "metrics-after.txt").read_text()
        def token_series(raw):
            return {line.rsplit(" ", 1)[0]: float(line.rsplit(" ", 1)[1])
                    for line in raw.splitlines() if line.startswith("gen_ai_client_token_usage_total{")}
        b, a = token_series(before), token_series(after)
        delta = {key: a.get(key, 0) - b.get(key, 0) for key in set(b) | set(a)}
        assert all(value == 0 for value in delta.values()), delta
        row = {
            "run": run.name, "model": result["model"],
            "connectionsObservedInLog": connections,
            "utterancesUploaded": 3 if index == 6 else (2 if index == 5 else 1),
            "firstSubtitleFromFirstFrameMs": round(result["firstSubtitleMs"] - result["firstAudioSendMs"], 3),
            "firstFinalCallbackFromLastProvidedSpeechChunkMs": first_final_ms,
            "expectedNormalizedFirstTextMatch": result["normalizedExactTextMatch"],
            "sameConnectionRepetitionMatch": result.get("normalizedSameConnectionRepeatedTextMatch"),
            "reconnectedCombinedRepetitionMatch": result.get("normalizedReconnectedCombinedTextMatch"),
            "finalCallbackCountObserved": len(final_lines),
            "idleUnavailableObserved": result.get("idleUnavailableObserved", False),
            "reconnectReadyMs": result.get("reconnectReadyObservedMs"),
            "observedChatEmbeddingTokenCounterDelta": delta,
            "asrUsage": "unknown", "openingTtsUsage": "unknown-may-synthesize",
            "formalLatencyOrAccuracyGate": False,
        }
        assert result["ownSessionDeleted"] and result["originalPublicFingerprintUnchanged"]
        rows.append(row)
    assert sum(row["connectionsObservedInLog"] for row in rows) == 6
    assert sum(row["utterancesUploaded"] for row in rows) == 7
    assert rows[2]["reconnectedCombinedRepetitionMatch"] is False
    assert rows[3]["sameConnectionRepetitionMatch"] and rows[3]["reconnectedCombinedRepetitionMatch"]
    save(BASE / "application-asr-observations-20261005.json", {
        "capturedAt": datetime.now(timezone.utc).isoformat(), "runs": rows,
        "actualAsrConnections": 6, "uploadedUtterances": 7,
        "asrTokenUsage": "unknown-not-exposed", "openingTtsUsage": "unknown-not-exposed",
        "additionalAuthorizedApplicationLlmComparisons": 0,
        "sourceAudio": "20261004-r4/window-2/002-whole.pcm",
        "generalAsrAccuracy": "not-evaluated", "browserLatencyGate": "not-evaluated",
    })
    return rows


def main():
    tests = summarize_tests()
    rows = observations()
    table = "\n".join(
        f"| {r['run'].rsplit('-r', 1)[1]} | {r['model'].split('-')[2]} | "
        f"{r['utterancesUploaded']} / {r['connectionsObservedInLog']} | "
        f"{r['firstSubtitleFromFirstFrameMs']:.0f} | "
        f"{r['firstFinalCallbackFromLastProvidedSpeechChunkMs'] if r['firstFinalCallbackFromLastProvidedSpeechChunkMs'] is not None else '未观测'} |"
        for r in rows)
    report = f"""# 应用 ASR 复验与重复发言修复（2026-10-05）

## 保留与撤回决定

保留两项后端修复：配置的 Streaming 静音阈值实际传入 SDK；使用本地识别器生命周期 ID 与供应商句子 ID 区分发言。默认保持 ASR 3.0 / 2000ms，临时 3.1 / 800ms 已撤回，原本不存在的 providers.yml 恢复为不存在，.env 未改。

这次有真实回答丢失的修复证据，没有整体识别准确率、语音端到端 P95 或生产收益结论。S0–S4 总目标 active。

## 实际应用链路与复现

REST 创建自己的隔离会话 → 实际 WebSocket 上传 16kHz s16le 单声道 PCM → QwenAsrService → 现有阿里云 ASR → 应用字幕与合并缓冲区。复用一份已封存公开合成问题音频，100ms / 3200 字节帧节奏，附 3 秒静音。原 24k 音频按 resample_poly 2/3 转换并冻结；期望文本仅本地核对，不发给识别器。

- r5：3.1 / 800ms，第一次发言、空闲断连、恢复、重复同一发言。日志有两次实际 final 回调，应用合并文本却仅保留一次；合并完整性检查失败，负结果未改写。
- r6：同模型、同阈值、同 PCM，改为身份区分。同连接重复后保留两份；空闲断连并重连，再说一次后保留三份。两种重复发言检查均通过。
- 同一来源同一句的重复/修订回调只更新自己的条目；不同句子编号或新识别器的相同文本追加。无句子编号时仅在同一来源内保留原累计文本兼容逻辑；旧识别器迟到回调仍由原隔离规则挡住。
- partial 仅作预览，不能更改确认文本；已 final 句子的迟到 partial 不倒退显示。

## 观察时刻（单份音频诊断）

| 运行 | ASR 版本 | 发言数 / 识别连接数 | 首音频帧至首字幕 ms | 最后提供的语音帧至实际 final 回调 ms |
|---|---|---:|---:|---:|
{table}

r2 旧源码没有传入配置的静音阈值；r3/r5/r6 是显式 800ms。r2 没有 final DEBUG 回调时刻，不能把最后一条字幕当作 final。r6 的约 1188ms 与首字幕 437ms 不支持“800ms 已改善响应速度”；这不是随机配对延迟试验，也没有标注真正声学发言结束点。r5/r6 分别观察到约 22 秒空闲后不可用，恢复 ready 为 328/203ms，仅记录可恢复性，不得外推平均恢复速度。

## 参数依据与方案评审

当前接入的是 Streaming Recognition，字段为 max_sentence_silence，范围 200–6000ms。原配置存在但未传入 SDK；现在启用断句时显式传入，非法配置在创建识别器前以 BusinessException 原样拒绝。默认 2000ms 保持配置原值。[阿里云 Streaming Java SDK](https://help.aliyun.com/en/model-studio/qwen-audio-asr-streaming-java-sdk)

自评审结论：身份信息留在后端、兼容 Consumer<String>，不扩展客户端事件格式；运行 ID 每个识别器本地生成，不改变供应商请求；不使用字符串相等猜测“用户说过一次还是两次”。阈值降低需进一步随机配对、多内容与句尾保留检查。本轮不采用 800ms 默认值。

## 回归、失败和用量

最终新鲜全量后端回归：{tests['suites']} 组 / **{tests['tests']} 项，0 失败、0 错误、0 跳过**。新覆盖包含参数实际接线/非法边界、SDK 句子身份转发、同连接与重连重复、修订去重和迟到 partial。前一版 409 项证据保留；随后按项目规则改为保留 BusinessException 原异常，再次完整运行 409 项。未宣称本轮新前端构建。

真实 ASR 共 **6 个连接 / 7 次音频发言 / 4 个应用 WS 会话**。无提交给 LLM、无结束评估 API，Chat/Embedding 已暴露计数前后没有增加；旧 r2 的 Embedding 116 Token 是之前批次的累计值。ASR Token 与开场 TTS 用量未被应用暴露，均为未知，不能填 0 或估算。自动开场可能合成 TTS；客户端取消开场不等于没有供应商消耗。此前用户授权 8 次应用 Flash/27B 对照已用完，本轮没有追加。

失败保留：r1 因 sessionId 字段读错，REST 已创建自己的 31 号会话但未进 WS；定向删会话后发现缓存残留，仅删除已证实属于本次的缓存键，证据保留。r4 尝试复制原本不存在的可选 YAML 失败、模型守卫随后在 WS 前拒绝，供应商调用为 0。r5 的识别文本合并丢失是实际产品缺陷，不归为供应商不可用。最早 2 项参数接线测试失败、402/404/409 项各次回归原始 XML/日志保留。

数据恢复确认：15 公开文档、123 向量、0 语音会话、0 RAG 会话和消息；原四份公开资料/24 向量 SHA-256 为 76d903d460a4f34c5f3fedcc1e31b998cb999a6fb8d2ad6105760c1ed30b495b，与基线一致。

## 后续必选工作

1. 应用 ASR 多内容、技术词、句尾保留与真实客户端观察，严格区分字幕、final 与提交时刻；扩大后才能报告准确率或断句收益。
2. 语音实际浏览器 ≥100 有效样本/臂、≥3 时间窗，检查起播、间隙、取消、乱序、迟到音频和资源回收，沿用既定采用门。
3. RAG 使用冻结方案后未用于调参的新资料，分别验证必要证据覆盖与最终回答忠实度、完整性、引用支持、拒答及真实 SSE；既有固定片段负结果不能代替正式门。
4. S4 岗位化出题、文字/语音评估、训练任务与文字定向复测，以及既定异步故障边界；本轮 31 号会话发现的删除后缓存残留需修复与实测。

证据入口：application-asr-observations-20261005.json、runs/application-asr-20261005-r1 至 r6、runs/application-asr-segmentation-20261005-r1/r2、runs/application-asr-identity-20261005-r1/r2。每版最终 SHA 与精确密钥值扫描在封存记录中确认；封存后不得覆写，后续使用新版本。
"""
    report_file = BASE / "APPLICATION_ASR_RESULTS_2026-10-05.md"
    report_file.write_text(report, encoding="utf-8")
    last = RUNS / "application-asr-identity-20261005-r2"
    shutil.copyfile(report_file, last / report_file.name)
    shutil.copyfile(BASE / "application-asr-observations-20261005.json", last / "observations.json")
    print(json.dumps({"tests": tests, "actualAsrConnections": 6, "uploadedUtterances": 7,
                      "repeatedUtteranceFixVerified": True, "formalLatencyGate": False}))


if __name__ == "__main__":
    main()
