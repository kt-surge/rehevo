"""Seal the closed collector stdout and verify preserved initial artifacts/current sources/runtime.

The initial manifest is immutable. Its sole permitted discrepancy is the collector log,
captured empty before stdout closed. This helper also permits the optional providers.env to be absent.
"""
import json
from pathlib import Path
import re
import shutil
import sys

import requests
import yaml

ROOT = Path(__file__).resolve().parents[3]
RUN = Path(__file__).parent / "runs/vector-generation-20261001"
sys.path.insert(0, str(ROOT / "observability/experiments/rag-evaluation"))
from experimental_index_snapshot import current, vectors_hash
from fact_gold import digest
from ingest_primary_dev import api, BASE_URL, write_json


def secret_hits():
    local = ROOT / "data/local/rehevo-opt-runtime-20261001"
    values = set()
    for path in (local / ".env", local / "providers.env", ROOT / ".env",
                 Path.home() / ".rehevo/llm-providers.env"):
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            match = re.match(r"^([A-Z][A-Z0-9_-]*)=(.*)$", line)
            if match and re.search(r"KEY|SECRET|PASSWORD|TOKEN", match[1]) and len(match[2]) >= 8:
                values.add(match[2].strip().strip("\"'"))
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
    for path in (local / "providers.yml", Path.home() / ".rehevo/llm-providers.yml"):
        if path.exists():
            walk(yaml.safe_load(path.read_text(encoding="utf-8-sig")))
    return [path.relative_to(RUN).as_posix() for path in sorted(RUN.rglob("*")) if path.is_file()
            and any(value.encode(encoding) in path.read_bytes() for value in values
                    for encoding in ("utf-8", "utf-16-le", "utf-16-be"))]


def main():
    if any((RUN / name).exists() for name in ("artifacts-final.sha256.json", "artifact-finalization.json", "post-freeze-verification.json")):
        raise ValueError("Final evidence exists; do not overwrite")
    initial = json.loads((RUN / "artifacts.sha256.json").read_text(encoding="utf-8"))
    mismatches = [name for name, value in initial.items() if digest(RUN / name) != value]
    if mismatches != ["collector.log"] or initial["collector.log"] != "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855":
        raise ValueError("Unexpected initial artifact discrepancy")
    logged = json.loads((RUN / "collector.log").read_text(encoding="utf-8-sig").strip())
    comparison = json.loads((RUN / "comparison.json").read_text(encoding="utf-8"))
    if logged != {"baselineFailures": 2, "candidateRacePasses": 2, "protocolPasses": 6, "previousFaultPasses": 9,
                  "defaultTests": comparison["defaultBackendRegression"], "cleanup": comparison["cleanup"]}:
        raise ValueError("Closed collector stdout differs from required comparison")
    index = json.loads((RUN / "candidate-sources/file-index.json").read_text(encoding="utf-8"))
    changed = [name for name, frozen in index.items() if digest(ROOT / name) != digest(RUN / "candidate-sources" / frozen)]
    leaked = secret_hits()
    health = requests.get(BASE_URL + "/actuator/health", timeout=10)
    health.raise_for_status()
    status = health.json()
    rows = api("/api/knowledgebase/list")
    original = json.loads((ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001/index-backup-manifest.json").read_text(encoding="utf-8"))
    unchanged = vectors_hash(current()) == original["vectorsSha256"]
    if changed or leaked or status.get("status") != "UP" or not unchanged:
        raise ValueError("Source/credential/runtime verification failed; no values exported")
    if {row["id"] for row in rows} != {1, 2, 3, 4} or any(row["vectorStatus"] != "COMPLETED" for row in rows):
        raise ValueError("Runtime no longer contains only the four completed public documents")
    shutil.copyfile(Path(__file__), RUN / "final-verification-helper.py")
    write_json(RUN / "artifact-finalization.json", {
        "initialManifestPreserved": True, "initialCaptureDiscrepancies": mismatches,
        "reason": "Only collector stdout was initially captured while empty, before the collector printed its summary.",
        "closedCollectorLogSha256": digest(RUN / "collector.log"),
        "finalHelperSourceSha256": digest(Path(__file__)),
        "optionalCredentialFileAbsentHandled": True, "productionOrTestSourceChanged": False})
    final = {path.relative_to(RUN).as_posix(): digest(path) for path in sorted(RUN.rglob("*")) if path.is_file()}
    write_json(RUN / "artifacts-final.sha256.json", final)
    final_mismatches = [name for name, value in final.items() if digest(RUN / name) != value]
    if final_mismatches:
        raise ValueError("Final closed artifacts changed during verification")
    write_json(RUN / "post-freeze-verification.json", {
        "checksumManifest": "artifacts-final.sha256.json", "frozenArtifactCount": len(final),
        "hashMismatches": final_mismatches, "currentSourcesDifferFromFrozen": changed,
        "sensitiveValueHitFiles": leaked, "credentialValuesRecorded": False,
        "runtimeHealthHttpStatus": health.status_code, "runtimeHealth": status,
        "restoredKnowledgeBases": rows, "originalPublicIndexHashMatches": unchanged})
    print(json.dumps({"frozenArtifacts": len(final), "hashMismatches": len(final_mismatches),
                      "sourceChanges": len(changed), "sensitiveValueHitFiles": len(leaked),
                      "runtimeHealth": status["status"], "originalPublicIndexUnchanged": unchanged}))


if __name__ == "__main__":
    main()
