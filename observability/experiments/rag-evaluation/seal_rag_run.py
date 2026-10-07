"""Seal one new RAG run, preserve source and actual failures, scan existing credential values."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'observability/experiments/tts-streaming'))
from seal_voice_run import all_files, read_bytes


def main(run):
    run = run.resolve()
    allowed = ROOT / 'observability/experiments/rag-evaluation/runs'
    if not run.is_relative_to(allowed) or not run.is_dir():
        raise ValueError('Only an existing RAG experiment run may be sealed')
    manifest_path = run / 'artifacts.sha256.json'
    verification_path = run / 'seal-verification.json'
    if manifest_path.exists() or verification_path.exists():
        raise ValueError('Preserve the previous seal')
    keys = [os.environ.get(name, '').encode() for name in
            ('REHEVO_TTS_EXPERIMENT_API_KEY', 'AI_BAILIAN_API_KEY', 'ALI-API-KEY')]
    keys = [key for key in keys if key]
    if not keys:
        raise ValueError('Existing credential needed for exact leak scan; never recorded')
    for path in run.rglob('manifest.json'):
        manifest = json.loads(path.read_text(encoding='utf-8'))
        if not isinstance(manifest.get('sources'), list):
            continue
        for item in manifest['sources']:
            if hashlib.sha256(read_bytes(path.parent / item['file'])).hexdigest() != item['sha256']:
                raise ValueError('Frozen source changed')
    hashes = {}
    for path in sorted(all_files(run)):
        raw = read_bytes(path)
        if any(key in raw for key in keys):
            raise ValueError('Existing sensitive value found; no seal written')
        hashes[path.relative_to(run).as_posix()] = hashlib.sha256(raw).hexdigest()
    manifest_path.write_text(json.dumps(dict(sealedAt=datetime.now(timezone.utc).isoformat(),
        algorithm='SHA-256', files=hashes), indent=2), encoding='utf-8')
    bad = [name for name, digest in hashes.items() if hashlib.sha256(read_bytes(run / name)).hexdigest() != digest]
    result = dict(files=len(hashes), mismatches=bad, exactSensitiveValuesFound=0,
                  verifiedAt=datetime.now(timezone.utc).isoformat(),
                  note='Excludes manifest/verification; live application logs are outside this sealed run')
    verification_path.write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))
    return bool(bad)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('run', type=Path)
    raise SystemExit(main(parser.parse_args().run))
