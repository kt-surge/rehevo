"""Freeze RAG stream sources and related client/parser/tests, Windows long paths supported."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def native(path):
    return '\\\\?\\' + str(path.resolve()) if os.name == 'nt' else str(path)


def main(run, name):
    out = run.resolve() / name
    if not out.is_relative_to(ROOT / 'observability/experiments/rag-evaluation/runs'):
        raise ValueError('Only an experiment run may be frozen')
    if (out / 'manifest.json').exists():
        raise ValueError('Do not overwrite frozen sources')
    paths = [*ROOT.glob('app/src/main/java/interview/guide/modules/knowledgebase/**/*.java'),
        *ROOT.glob('app/src/main/java/interview/guide/infrastructure/stream/**/*.java'),
        *ROOT.glob('app/src/test/java/interview/guide/modules/knowledgebase/**/*.java'),
        ROOT / 'app/src/main/java/interview/guide/infrastructure/mapper/RagChatMapper.java',
        ROOT / 'app/src/main/java/interview/guide/common/metrics/ApplicationMetrics.java',
        ROOT / 'app/src/main/java/interview/guide/common/metrics/AppMetricNames.java',
        ROOT / 'app/src/main/java/interview/guide/common/exception/ErrorCode.java',
        ROOT / 'app/src/main/java/interview/guide/common/ai/LlmProviderRegistry.java',
        ROOT / 'app/src/test/java/interview/guide/common/ai/LlmProviderRegistryPathIntegrationTest.java',
        ROOT / 'app/src/main/resources/application.yml', ROOT / 'app/build.gradle',
        ROOT / 'gradle/libs.versions.toml', ROOT / 'settings.gradle',
        ROOT / 'observability/experiments/rag-evaluation/SdkWireCancellationProbe.java',
        ROOT / 'observability/experiments/rag-evaluation/sdk-wire.init.gradle',
        ROOT / 'frontend/src/pages/KnowledgeBaseQueryPage.tsx', ROOT / 'frontend/src/api/ragChat.ts',
        ROOT / 'frontend/src/api/stream.ts', *ROOT.glob('frontend/src/types/rag*.ts'),
        ROOT / 'frontend/src/utils/ragStreamProtocol.ts',
        ROOT / 'frontend/package.json', ROOT / 'frontend/pnpm-lock.yaml',
        ROOT / 'observability/experiments/rag-evaluation/rag-stream-terminal-migration.sql',
        ROOT / 'observability/experiments/rag-evaluation/verify_rag_stream.mjs',
        ROOT / 'observability/experiments/rag-evaluation/freeze_rag_terminal_sources.py']
    manifest = []
    for path in paths:
        relative = path.relative_to(ROOT)
        target = out / relative
        os.makedirs(native(target.parent), exist_ok=True)
        with open(native(path), 'rb') as handle:
            raw = handle.read()
        with open(native(target), 'xb') as handle:
            handle.write(raw)
        manifest.append(dict(file=relative.as_posix(), sha256=hashlib.sha256(raw).hexdigest()))
    (out / 'manifest.json').write_text(json.dumps(dict(at=datetime.now(timezone.utc).isoformat(),
        sources=manifest), indent=2), encoding='utf-8')
    print(json.dumps(dict(frozenFiles=len(manifest), name=name)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('run', type=Path)
    parser.add_argument('name', choices=['baseline-source', 'candidate-source'])
    args = parser.parse_args()
    main(args.run, args.name)
