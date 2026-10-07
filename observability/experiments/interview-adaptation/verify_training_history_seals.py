"""Independent verification of the three completed, immutable training-history runs."""
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
NAMES = ("training-history-baseline-20261005-r1", "training-history-candidate-20261005-r1",
         "training-history-product-20261005-r1")


def read(path):
    return Path("\\\\?\\" + str(path.resolve())).read_bytes() if os.name == "nt" else path.read_bytes()


results = []
for name in NAMES:
    run = ROOT / "observability/experiments/voice-frame-pipeline/runs" / name
    manifest = json.loads(read(run / "artifacts.sha256.json"))
    mismatches = [file for file, expected in manifest["files"].items()
                  if hashlib.sha256(read(run / file)).hexdigest() != expected]
    results.append(dict(run=name, files=len(manifest["files"]), mismatches=mismatches))
result = dict(verifiedAt=datetime.now(timezone.utc).isoformat(), method="independent SHA-256 recomputation",
              runs=results, totalFiles=sum(item["files"] for item in results))
output = ROOT / "observability/experiments/interview-adaptation/training-history-seal-verification-20261005.json"
if output.exists():
    raise ValueError("Preserve prior independent verification")
output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(result, indent=2))
if any(item["mismatches"] for item in results):
    raise SystemExit(1)
