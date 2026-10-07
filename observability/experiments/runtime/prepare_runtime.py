"""Create local-only credentials once and record cached image identities."""
import json
from pathlib import Path
import secrets
import subprocess

ROOT = Path(__file__).resolve().parents[3]
DIRECTORY = ROOT / "data/local/rehevo-opt-runtime-20261001"
IMAGES = ["pgvector/pgvector:pg16", "redis:7", "rustfs/rustfs:latest"]


def main():
    if DIRECTORY.exists():
        raise ValueError("Runtime directory exists; reuse its credentials and volumes, do not regenerate")
    images = {}
    for name in IMAGES:
        raw = subprocess.check_output(["docker", "image", "inspect", name,
                                       "--format", "{{json .Id}}|{{json .RepoDigests}}"], text=True).strip()
        identity, digests = raw.split("|", 1)
        images[name] = {"imageId": json.loads(identity), "repoDigests": json.loads(digests)}
    DIRECTORY.mkdir(parents=True)
    settings = {"POSTGRES_HOST": "127.0.0.1", "POSTGRES_PORT": "15439", "POSTGRES_USER": "postgres",
                "POSTGRES_DB": "rehevo_opt", "POSTGRES_PASSWORD": secrets.token_urlsafe(24),
                "REDIS_HOST": "127.0.0.1", "REDIS_PORT": "16387",
                "APP_STORAGE_ENDPOINT": "http://127.0.0.1:19087", "APP_STORAGE_BUCKET": "rehevo-opt",
                "RUSTFS_ACCESS_KEY": "rehevo-exp-" + secrets.token_hex(8),
                "RUSTFS_SECRET_KEY": secrets.token_urlsafe(32),
                "APP_AI_CONFIG_ENCRYPTION_KEY": secrets.token_urlsafe(32),
                "CORS_ALLOWED_ORIGINS": "http://127.0.0.1:5187"}
    env_path = DIRECTORY / ".env"
    ignored = subprocess.run(["git", "check-ignore", "--quiet", str(env_path)], cwd=ROOT)
    if ignored.returncode != 0:
        raise ValueError("Credential path is not ignored by Git")
    env_path.write_text("".join(key + "=" + value + "\n" for key, value in settings.items()), encoding="utf-8")
    manifest = {"kind": "isolated-optimization-runtime", "composeProject": "rehevo-opt-20261001",
                "images": images, "apiPort": 18080, "bindAddress": "127.0.0.1",
                "credentialValuesRecorded": False, "settingsNames": sorted(settings),
                "note": "Cached images only. New empty volumes and provider configuration paths; no other containers are modified."}
    (DIRECTORY / "runtime.manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"runtimeDirectory": str(DIRECTORY), "images": images, "credentialValuesRecorded": False}))


if __name__ == "__main__":
    main()
