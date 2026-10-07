"""Check frozen experiment hashes/current source, secret leakage and restored runtime, without exporting credentials."""
import json
from pathlib import Path
import re
import sys

import requests
import yaml

ROOT = Path(__file__).resolve().parents[3]
RUN = Path(__file__).parent / "runs/vector-generation-20261001"
sys.path.insert(0, str(ROOT / "observability/experiments/rag-evaluation"))
from experimental_index_snapshot import current, vectors_hash
from fact_gold import digest
from ingest_primary_dev import api, BASE_URL, write_json


def credentials():
    local = ROOT / "data/local/rehevo-opt-runtime-20261001"
    values = set()
    for filename in (".env", "providers.env"):
        for line in (local / filename).read_text(encoding="utf-8-sig").splitlines():
            match = re.match(r"^([A-Z][A-Z0-9_]*)=(.*)$", line)
            if match and re.search(r"KEY|SECRET|PASSWORD|TOKEN", match[1]) and len(match[2]) >= 8:
                values.add(match[2])
    def walk(value):
        if isinstance(value, dict):
            for key, item in value.items():
                if re.search(r"key|secret|password|token", str(key), re.I) and isinstance(item, str):
                    if len(item) >= 8 and not item.startswith("${"):
                        values.add(item)
                walk(item)
        elif isinstance(value, list):
            for item in value:
                walk(item)
    walk(yaml.safe_load((local / "providers.yml").read_text(encoding="utf-8-sig")))
    return values


def secret_hits():
    secrets = credentials()
    matches = []
    for path in sorted(RUN.rglob("*")):
        if not path.is_file():
            continue
        data = path.read_bytes()
        if any(value.encode(encoding) in data for value in secrets for encoding in ("utf-8", "utf-16-le", "utf-16-be")):
            matches.append(path.relative_to(RUN).as_posix())
    return matches


def main():
    if (RUN / "post-freeze-verification.json").exists():
        raise ValueError("Verification evidence exists; do not overwrite")
    hashes = json.loads((RUN / "artifacts.sha256.json").read_text(encoding="utf-8"))
    mismatches = [name for name, value in hashes.items() if digest(RUN / name) != value]
    index = json.loads((RUN / "candidate-sources/file-index.json").read_text(encoding="utf-8"))
    changed = [name for name, frozen in index.items() if digest(ROOT / name) != digest(RUN / "candidate-sources" / frozen)]
    leaked = secret_hits()
    health = requests.get(BASE_URL + "/actuator/health", timeout=10)
    health.raise_for_status()
    status = health.json()
    rows = api("/api/knowledgebase/list")
    original = json.loads((ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001/index-backup-manifest.json").read_text(encoding="utf-8"))
    unchanged = vectors_hash(current()) == original["vectorsSha256"]
    result = {"frozenArtifactCount": len(hashes), "hashMismatches": mismatches,
              "currentSourcesDifferFromFrozen": changed, "sensitiveValueHitFiles": leaked,
              "credentialValuesRecorded": False, "runtimeHealthHttpStatus": health.status_code,
              "runtimeHealth": status, "restoredKnowledgeBases": rows, "originalPublicIndexHashMatches": unchanged}
    if mismatches or changed or leaked or status.get("status") != "UP" or not unchanged:
        raise ValueError("Evidence/current source/runtime verification gate failed; inspect without exporting credentials")
    if {row["id"] for row in rows} != {1, 2, 3, 4} or any(row["vectorStatus"] != "COMPLETED" for row in rows):
        raise ValueError("Restored runtime differs from four completed public documents")
    write_json(RUN / "post-freeze-verification.json", result)
    print(json.dumps({"frozenArtifacts": len(hashes), "hashMismatches": len(mismatches),
                      "sourceChanges": len(changed), "sensitiveValueHitFiles": len(leaked),
                      "runtimeHealth": status["status"], "originalPublicIndexUnchanged": unchanged}))


if __name__ == "__main__":
    main()
