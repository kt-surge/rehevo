"""Snapshot actual voice/context sources and public Skill inputs before external calls."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[3]


def main(output, different_question_sources=False):
    output.mkdir(parents=True, exist_ok=False)
    names = set()
    for directory in ['app/src/main/java/interview/guide/modules/voiceinterview',
                      'app/src/main/java/interview/guide/common/ai',
                      'app/src/main/java/interview/guide/modules/interview/skill',
                      'app/src/main/resources/skills']:
        for file in (ROOT / directory).rglob('*'):
            if file.is_file():
                names.add(file.relative_to(ROOT).as_posix())
    for file in (ROOT / 'app/src/main/resources/prompts').rglob('*voice*'):
        if file.is_file():
            names.add(file.relative_to(ROOT).as_posix())
    for directory in ['frontend/src/types', 'frontend/src/utils']:
        for file in (ROOT / directory).glob('voice*'):
            if file.is_file():
                names.add(file.relative_to(ROOT).as_posix())
    for name in ['frontend/src/pages/VoiceInterviewPage.tsx', 'frontend/src/api/voiceInterview.ts',
                 'frontend/src/api/request.ts', 'frontend/vite.config.ts', 'frontend/package.json',
                 'frontend/pnpm-lock.yaml', 'app/src/main/resources/application.yml',
                 'observability/experiments/tts-streaming/SdkUsageTap.java']:
        names.add(name)
    directory = ROOT / 'observability/experiments/voice-frame-pipeline'
    for file in directory.iterdir():
        if file.is_file() and file.suffix in ('.py', '.java', '.gradle', '.ps1', '.json', '.md'):
            names.add(file.relative_to(ROOT).as_posix())
    files = []
    for index, name in enumerate(sorted(names)):
        raw = (ROOT / name).read_bytes()
        frozen = f'{index:03d}.source'
        (output / frozen).write_bytes(raw)
        files.append(dict(path=name, frozen=frozen, sha256=hashlib.sha256(raw).hexdigest()))
    sdk = []
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1'
    for group, artifact, version in [('org.springframework.ai','spring-ai-openai','2.0.0'),
                                     ('com.openai','openai-java-core','4.39.1'),
                                     ('com.alibaba','dashscope-sdk-java','2.22.7')]:
        jars = list((cache / group / artifact / version).rglob(f'{artifact}-{version}.jar'))
        if len(jars) != 1:
            raise ValueError(f'Expected one actual SDK jar: {artifact}')
        sdk.append(dict(artifact=f'{group}:{artifact}:{version}',
                        sha256=hashlib.sha256(jars[0].read_bytes()).hexdigest()))
    dirty = subprocess.check_output(['git','status','--porcelain'],cwd=ROOT)
    manifest = dict(frozenAt=datetime.now(timezone.utc).isoformat(), files=files, sdk=sdk,
                    gitHead=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT).decode().strip(),
                    gitDirtySha256=hashlib.sha256(dirty).hexdigest(),
                    sameSourceForBothArms=not different_question_sources)
    (output / 'source-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(dict(files=len(files), sdkArtifacts=len(sdk), frozenAt=manifest['frozenAt'])))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('output',type=Path)
    parser.add_argument('--different-question-sources', action='store_true')
    args = parser.parse_args()
    main(args.output, args.different_question_sources)
