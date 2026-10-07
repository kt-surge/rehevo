"""Freeze compact factual repairs separately; prior live outputs apply to r1 only."""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
old = BASE / 'reference-facts-source-baseline-20261006-r1'
suffix = sys.argv[1] if len(sys.argv) > 1 else 'r2'
assert suffix in ('r2', 'r3', 'r4')
run = BASE / f'reference-facts-source-candidate-20261006-{suffix}'
run.mkdir()
(run / 'sources').mkdir()
(run / 'references').mkdir()
prior = json.loads((old / 'source-manifest.json').read_text(encoding='utf-8'))
changed = []
manifest = []
for item in prior['files']:
    path = ROOT / item['path']
    raw = path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != item['sha256']:
        changed.append(item['path'])
    (run / item['frozen']).write_bytes(raw)
    if path.name in ('spring.md', 'mysql.md', 'mq.md'):
        (run / 'references' / path.name).write_bytes(raw)
    manifest.append(dict(item, sha256=digest))
expected = [f'app/src/main/resources/skills/_shared/references/{n}' for n in ('spring.md', 'mysql.md', 'mq.md')]
assert sorted(changed) == sorted(expected), changed
(run / 'source-manifest.json').write_text(json.dumps(dict(files=manifest), indent=2), encoding='utf-8')
(run / 'comparison-changes.json').write_text(json.dumps(dict(changed=changed,
    promptsCodeSchemaUnchanged=True, liveInferenceRunsForThisVariant=0,
    r1LiveOutputsDoNotDescribeThisVariant=True, versionReason='restore normal Skill reference coverage'), indent=2), encoding='utf-8')
(run / 'prepare_compact_references.py').write_bytes(Path(__file__).read_bytes())
print(json.dumps(dict(prepared=True, files=len(manifest), chars={name:len((run/'references'/name).read_text(encoding='utf-8')) for name in ('spring.md','mysql.md','mq.md')}, externalModelCalls=0)))
