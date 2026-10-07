"""Freeze retained diagnostics and verify hashes; refuse to overwrite an existing seal."""
import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path


def read_bytes(path):
    value = str(path.resolve())
    if os.name == 'nt':
        value = '\\\\?\\' + value
    with open(value, 'rb') as file:
        return file.read()


def all_files(root):
    value = str(root.resolve())
    if os.name == 'nt':
        value = '\\\\?\\' + value
    for directory, _, names in os.walk(value):
        for name in names:
            yield Path(str(Path(directory) / name).removeprefix('\\\\?\\'))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=Path)
    args = parser.parse_args()
    root = args.run.resolve()
    repository = Path(__file__).resolve().parents[3]
    allowed = repository / "observability/experiments/voice-frame-pipeline/runs"
    if not root.is_relative_to(allowed) or not root.is_dir():
        raise ValueError("Run must be an existing voice experiment directory")
    manifest_file = root / "artifacts.sha256.json"
    verification_file = root / "seal-verification.json"
    if manifest_file.exists() or verification_file.exists():
        raise ValueError("Seal exists; preserve evidence")
    sensitive = [os.environ.get(name, "").encode() for name in
                 ("REHEVO_TTS_EXPERIMENT_API_KEY", "AI_BAILIAN_API_KEY", "ALI-API-KEY")]
    sensitive = [value for value in sensitive if value]
    if not sensitive:
        raise ValueError("Existing credential required for exact-value leak scan; it is never exported")
    hashes = {}
    for file in sorted(all_files(root)):
        contents = read_bytes(file)
        if any(value in contents for value in sensitive):
            raise ValueError(f"Exact sensitive value found in {file.relative_to(root)}; no seal written")
        hashes[file.relative_to(root).as_posix()] = hashlib.sha256(contents).hexdigest()
    for manifest_path in root.rglob("source-manifest.json"):
        manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
        for name, item in manifest.get("sources", {}).items():
            frozen = manifest_path.parent / "sources" / item["file"]
            if hashlib.sha256(read_bytes(frozen)).hexdigest() != item["sha256"]:
                raise ValueError(f"Frozen source mismatch: {name}")
        for item in manifest.get("files", []):
            frozen = manifest_path.parent / item["frozen"]
            if hashlib.sha256(read_bytes(frozen)).hexdigest() != item["sha256"]:
                raise ValueError(f"Frozen source mismatch: {item['path']}")
    for manifest_path in root.rglob("manifest.json"):
        manifest = json.loads(manifest_path.read_text(encoding="utf-8-sig"))
        if not isinstance(manifest, dict) or not isinstance(manifest.get("sources"), list):
            continue
        for item in manifest["sources"]:
            frozen = manifest_path.parent / item["file"]
            if hashlib.sha256(read_bytes(frozen)).hexdigest() != item["sha256"]:
                raise ValueError(f"Frozen source mismatch: {item['path']}")
    manifest = {"sealedAt": datetime.now(timezone.utc).isoformat(), "algorithm": "SHA-256", "files": hashes}
    manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    mismatches = [name for name, expected in hashes.items()
                  if hashlib.sha256(read_bytes(root / name)).hexdigest() != expected]
    verification = dict(verifiedAt=datetime.now(timezone.utc).isoformat(), files=len(hashes),
                        mismatches=mismatches, exactSensitiveValuesFound=0,
                        note="verification excludes the manifest and this verification record")
    verification_file.write_text(json.dumps(verification, indent=2), encoding="utf-8")
    print(json.dumps(verification, indent=2))
    return bool(mismatches)


if __name__ == "__main__":
    raise SystemExit(main())
