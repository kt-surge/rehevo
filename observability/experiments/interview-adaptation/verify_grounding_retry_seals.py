"""Independent recomputation; preserves previously sealed negative and positive runs."""
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
NAMES = (
    "training-history-baseline-20261005-r1", "training-history-candidate-20261005-r1",
    "training-history-product-20261005-r1", "evaluation-grounding-baseline-20261005-r1",
    "evaluation-grounding-candidate-20261005-r1", "evaluation-grounding-candidate-20261005-r2",
    "evaluation-grounding-live-baseline-20261005-r1", "evaluation-grounding-live-candidate-20261005-r1",
    "structured-retry-baseline-20261005-r1", "structured-retry-baseline-20261005-r2",
    "structured-retry-baseline-20261005-r3", "structured-retry-candidate-20261005-r1")


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
output = ROOT / "observability/experiments/interview-adaptation/grounding-retry-seal-verification-20261005.json"
if output.exists():
    raise ValueError("Preserve earlier verification")
output.write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result, indent=2))
if any(item["mismatches"] for item in results):
    raise SystemExit(1)
