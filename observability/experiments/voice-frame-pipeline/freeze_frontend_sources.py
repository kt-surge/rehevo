"""Freeze browser/audio source revisions without private runtime settings."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


ROOT = Path(__file__).resolve().parents[3]
SOURCES = [
    'frontend/src/pages/VoiceInterviewPage.tsx',
    'frontend/src/api/voiceInterview.ts',
    'frontend/src/types/voiceAudio.ts',
    'frontend/src/types/voiceTelemetry.ts',
    'frontend/src/utils/voiceAudioScheduler.ts',
    'frontend/src/utils/voicePcmDecoder.ts',
    'frontend/src/utils/voiceTurnTelemetry.ts',
    'frontend/src/utils/voicePcmPlaybackObserver.ts',
    'frontend/src/utils/voiceInterviewSocket.ts',
    'frontend/vite.config.ts',
    'frontend/package.json',
    'app/src/main/java/interview/guide/modules/voiceinterview/dto/VoiceClientPlaybackReport.java',
    'app/src/main/java/interview/guide/common/metrics/ApplicationMetrics.java',
    'METRICS_CONTRACT.md',
    'observability/experiments/voice-frame-pipeline/controlled_browser_server.py',
    'observability/experiments/voice-frame-pipeline/test-audio-scheduler.mjs',
    'observability/experiments/voice-frame-pipeline/test-voice-socket.mjs',
]


def freeze(output):
    output.mkdir(parents=True, exist_ok=False)
    entries = []
    for index, relative in enumerate(SOURCES):
        source = ROOT / relative
        if not source.exists():
            continue
        name = f'{index:03d}.source'
        shutil.copyfile(source, output / name)
        entries.append(dict(path=relative, frozen=name, sha256=hashlib.sha256(source.read_bytes()).hexdigest()))
    (output / 'source-manifest.json').write_text(json.dumps(dict(files=entries), ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(output=str(output), frozenFiles=len(entries))))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    freeze(parser.parse_args().output)
