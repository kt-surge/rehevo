"""Freeze current RAG source; preserve partial failures and support Windows long paths."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def native(path):
    return '\\\\?\\' + str(path.resolve()) if os.name == 'nt' else str(path)


def freeze(run, name):
    out = run / name
    if (out / 'manifest.json').exists():
        raise ValueError('Frozen source cannot be overwritten')
    paths = [*ROOT.glob('app/src/main/java/interview/guide/modules/knowledgebase/**/*.java'),
             *ROOT.glob('app/src/main/resources/prompts/knowledgebase*.st'),
             ROOT / 'app/src/main/resources/application.yml',
             ROOT / 'frontend/src/api/ragChat.ts', ROOT / 'frontend/src/api/knowledgebase.ts',
             ROOT / 'frontend/src/pages/KnowledgeBaseQueryPage.tsx', ROOT / 'frontend/package.json',
             ROOT / 'frontend/pnpm-lock.yaml',
             *ROOT.glob('frontend/src/types/ragCitation.ts'),
             *ROOT.glob('frontend/src/utils/ragCitation*.ts')]
    sources = []
    for path in paths:
        relative = path.relative_to(ROOT)
        target = out / relative
        os.makedirs(native(target.parent), exist_ok=True)
        with open(native(path), 'rb') as f:
            raw = f.read()
        if target.exists():
            with open(native(target), 'rb') as f:
                if f.read() != raw:
                    raise ValueError('Partial snapshot differs; preserve and inspect')
        else:
            with open(native(target), 'xb') as f:
                f.write(raw)
        sources.append(dict(file=relative.as_posix(), sha256=hashlib.sha256(raw).hexdigest()))
    (out / 'manifest.json').write_text(json.dumps(dict(at=datetime.now(timezone.utc).isoformat(),
        sources=sources), ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(dict(frozenFiles=len(sources), destination=str(out))))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('run', type=Path)
    parser.add_argument('name', choices=['baseline-source', 'candidate-source', 'candidate-source-v2',
                                       'candidate-source-v3', 'candidate-source-v4'])
    args = parser.parse_args()
    freeze(args.run, args.name)
