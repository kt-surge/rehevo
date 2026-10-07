"""One configured-provider request; no credentials or response vectors logged."""
import hashlib
import json
import math
import os
from pathlib import Path
import time
import winreg

import requests
import yaml

ROOT = Path(__file__).resolve().parents[3]
OUTPUT = Path(__file__).resolve().parent / "runs/embedding-preflight-20261001.json"


def credential():
    for name in ("AI_BAILIAN_API_KEY", "ALI-API-KEY"):
        value = os.environ.get(name)
        if value:
            return value, name + ":process"
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, "Environment") as registry:
                value = winreg.QueryValueEx(registry, name)[0]
                if isinstance(value, str) and value:
                    return value, name + ":user"
        except FileNotFoundError:
            pass
    return None, None


def main():
    if OUTPUT.exists():
        raise ValueError("Preflight result already exists; use an explicit new run/version")
    config_path = ROOT / "app/src/main/resources/application.yml"
    provider = yaml.safe_load(config_path.read_text(encoding="utf-8"))["app"]["ai"]["providers"]["dashscope"]
    model, dimensions = provider["embedding-model"], provider["embedding-dimensions"]
    url = provider["base-url"].rstrip("/") + "/embeddings"
    text = "Rehevo factual-retrieval preflight."
    payload = {"model": model, "input": [text], "dimensions": dimensions, "encoding_format": "float"}
    key, key_source = credential()
    record = {"kind": "configured_embedding_provider_preflight", "evidenceType": "real_component_smoke",
              "configurationScope": "classpath dashscope defaults; not an effective running-app configuration",
              "configurationSha256": hashlib.sha256(config_path.read_bytes()).hexdigest(),
              "model": model, "dimensions": dimensions, "url": url,
              "inputSha256": hashlib.sha256(text.encode()).hexdigest(),
              "credentialAvailable": key is not None, "credentialSource": key_source,
              "requests": 0, "status": "credential-missing", "latencyEffectClaim": False}
    if key:
        start = time.perf_counter()
        record["requests"] = 1
        try:
            response = requests.post(url, headers={"Authorization": "Bearer " + key},
                                     json=payload, timeout=(10, 45))
            record["httpStatus"] = response.status_code
            try:
                result = response.json()
            except ValueError:
                result = {}
            if response.status_code == 200:
                vector = result.get("data", [{}])[0].get("embedding", [])
                valid = len(vector) == dimensions and all(type(item) in (int, float) and math.isfinite(item)
                                                         for item in vector) and any(item != 0 for item in vector)
                record.update(status="valid-vector" if valid else "invalid-vector", actualDimensions=len(vector),
                              usage=result.get("usage", {}))
                if valid:
                    record["vectorSha256"] = hashlib.sha256(json.dumps(vector).encode()).hexdigest()
            else:
                error = result.get("error", {})
                if not isinstance(error, dict):
                    error = {}
                code = str(error.get("code", result.get("code", "unknown")))
                message = str(error.get("message", result.get("message", "")))
                record.update(status="provider-error", providerCode=code.replace(key, "[redacted]")[:200],
                              providerMessage=message.replace(key, "[redacted]")[:500])
        except requests.RequestException as error:
            record.update(status="transport-error", errorType=type(error).__name__)
        record["elapsedMs"] = (time.perf_counter() - start) * 1000
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(record, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
