"""Independent exact membership and SHA-256 verification of the selected sealed runs."""
import hashlib
import json
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / 'observability/experiments/voice-frame-pipeline/runs'
output = (ROOT / sys.argv[1]).resolve()
assert output.is_relative_to(ROOT) and not output.exists()
proof = []
for name in sys.argv[2:]:
    run = (BASE / name).resolve()
    assert run.is_relative_to(BASE)
    seal = json.loads((run / 'artifacts.sha256.json').read_text(encoding='utf-8'))
    current = {}
    for directory, _, files in os.walk('\\\\?\\' + str(run) if os.name == 'nt' else str(run)):
        for filename in files:
            file = Path(directory, filename)
            relative = Path(str(file).removeprefix('\\\\?\\')).relative_to(run).as_posix()
            if relative in ('artifacts.sha256.json', 'seal-verification.json'):
                continue
            current[relative] = hashlib.sha256(file.read_bytes()).hexdigest()
    assert current == seal['files'], f'Hash or exact membership drift: {name}'
    proof.append(dict(run=name, files=len(current), hashAndMembershipMatched=True))
result = dict(runs=proof, totalFiles=sum(item['files'] for item in proof), allMatched=True)
output.write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result))
