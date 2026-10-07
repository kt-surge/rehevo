"""Collect local regression and controlled-browser evidence; no effect claim."""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
DIRECTORY = Path(__file__).resolve().parent
DESTINATION = DIRECTORY / "candidate-20261001"
SOURCE_PATHS = [
    "app/src/main/java/interview/guide/common/metrics/AppMetricNames.java",
    "app/src/main/java/interview/guide/common/metrics/ApplicationMetrics.java",
    "app/src/main/java/interview/guide/modules/voiceinterview/handler/VoiceInterviewWebSocketHandler.java",
    "app/src/main/java/interview/guide/modules/voiceinterview/dto/VoiceClientPlaybackReport.java",
    "app/src/main/resources/application.yml",
    "frontend/src/pages/VoiceInterviewPage.tsx",
    "frontend/src/api/voiceInterview.ts",
    "frontend/src/types/voiceTelemetry.ts",
    "frontend/src/utils/voiceTurnTelemetry.ts",
    "METRICS_CONTRACT.md",
    "observability/grafana/dashboards/voice-experience.json",
]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def freeze_captured_log():
    summary_path = DESTINATION / "summary.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    expected = summary["browser"]["fixtureLogSha256"]
    log_path = DIRECTORY / "runs/browser-20261001-correct-protocol.jsonl"
    captured = bytearray()
    for line in log_path.read_bytes().splitlines(keepends=True):
        captured.extend(line)
        if hashlib.sha256(captured).hexdigest() == expected:
            target = DESTINATION / "frozen-browser-protocol.jsonl"
            if target.exists():
                raise ValueError("Frozen protocol already exists")
            target.write_bytes(captured)
            summary["browser"]["fixtureLogPath"] = target.name
            summary["browser"]["captureBoundary"] = "Before browser cleanup; closure events remain in the complete run log"
            summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(json.dumps({"frozenLogSha256": expected, "bytes": len(captured)}))
            return
    raise ValueError("Captured protocol hash not found; refusing to manufacture a snapshot")


def main():
    if DESTINATION.exists():
        raise ValueError("Evidence snapshot already exists; do not overwrite")
    DESTINATION.mkdir()
    source_hashes = {}
    for relative in SOURCE_PATHS:
        source = ROOT / relative
        source_hashes[relative] = digest(source)
        target = DESTINATION / "sources" / (relative + ".txt")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    xml_directory = DESTINATION / "backend-test-results"
    xml_directory.mkdir()
    suites = []
    for source in (ROOT / "app/build/test-results/test").glob("TEST-*.xml"):
        shutil.copyfile(source, xml_directory / source.name)
        suites.append(ET.parse(source).getroot())
    totals = {key: sum(int(suite.get(key, 0)) for suite in suites)
              for key in ("tests", "failures", "errors", "skipped")}
    totals["suites"] = len(suites)
    log_path = DIRECTORY / "runs/browser-20261001-correct-protocol.jsonl"
    events = [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines() if line]
    scenarios = {event["turnId"]: event["mode"] for event in events if event["kind"] == "turn_scenario"}
    reports = [event["data"] for event in events
               if event["kind"] == "client_control" and event.get("action") == "playback_observed"]
    for report in reports:
        assert scenarios.get(report["turnId"]) in ("pcm", "html")
        assert 0 <= report["submitToAudioReceivedMs"] <= report["submitToPlaybackStartMs"] <= 120000
    assert len({report["turnId"] for report in reports}) == len(reports)
    assert not any(scenarios[report["turnId"]] in ("cancel", "failure") for report in reports)
    result = {"evidenceType": "local_regression_and_controlled_browser",
              "backendFreshTestCommand": "gradlew.bat :app:test --no-daemon --rerun-tasks",
              "backend": totals, "nodeTelemetryUnitTests": 8, "nodeTelemetryUnitFailures": 0,
              "frontendProductionBuild": "tsc && vite build: passed, 2026-10-01",
              "browser": {"fixtureLogSha256": digest(log_path), "actualSubmitCount": len(scenarios),
                          "scenarioCounts": {mode: list(scenarios.values()).count(mode)
                                             for mode in ("pcm", "html", "cancel", "failure")},
                          "reportCount": len(reports), "reports": reports,
                          "duplicateTurnReports": 0, "cancelledOrFailedTurnReports": 0,
                          "domAssertions": {"lateCancelledTextVisible": False,
                                            "lateFailureTextVisible": False,
                                            "canSubmitAfterFailure": True},
                          "screenshot": "runs/browser-latest.png"},
              "sourceSha256": source_hashes,
              "limitations": ["Controlled tone and protocol, not real ASR/LLM/TTS",
                              "Browser in-app Chromium only; no microphone or physical output verification",
                              "No provider or production latency improvement is inferred",
                              "Node/build/DOM statuses are the executed checks recorded by the agent; XML and protocol log are retained"]}
    (DESTINATION / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                                                encoding="utf-8")
    print(json.dumps({"backend": totals, "browserSubmits": len(scenarios),
                      "browserReports": len(reports), "sourceFiles": len(source_hashes)}, ensure_ascii=False))


if __name__ == "__main__":
    freeze_captured_log() if "--freeze-log" in sys.argv else main()
