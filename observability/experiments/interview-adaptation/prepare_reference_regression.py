"""Freeze the current backend and reference loader audit before fresh regression."""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
suffix = sys.argv[1] if len(sys.argv) > 1 else 'r1'
assert suffix in ('r1', 'r2', 'r3', 'r4')
run = ROOT / f'observability/experiments/voice-frame-pipeline/runs/reference-facts-regression-20261006-{suffix}'
run.mkdir()
(run / 'sources').mkdir()
paths = list((ROOT / 'app/src').rglob('*.java'))
paths += [p for p in (ROOT / 'app/src/main/resources').rglob('*') if p.is_file()]
paths += [ROOT / name for name in (
    'app/build.gradle', 'build.gradle', 'settings.gradle',
    'observability/experiments/interview-adaptation/ReferenceContextAuditMain.java',
    'observability/experiments/interview-adaptation/reference-context-audit.init.gradle',
    'observability/experiments/interview-adaptation/prepare_reference_regression.py',
    'observability/experiments/interview-adaptation/collect_training_target_tests.py')]
manifest = []
for i, path in enumerate(sorted(set(paths))):
    if not path.exists():
        continue
    raw = path.read_bytes()
    frozen = f'sources/{i:04d}-{path.name}'
    (run / frozen).write_bytes(raw)
    manifest.append(dict(path=path.relative_to(ROOT).as_posix(), frozen=frozen,
        sha256=hashlib.sha256(raw).hexdigest()))
(run / 'source-manifest.json').write_text(json.dumps(dict(files=manifest), indent=2), encoding='utf-8')
print(json.dumps(dict(prepared=True, sources=len(manifest), externalModelCalls=0)))
