"""Existing isolated provider keys in memory only, for exact-value artifact scans."""
import base64
import hashlib
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from probe_training_history_product import query


def existing_scoped_credentials():
    root = Path(__file__).resolve().parents[3]
    file = root / 'data/local/rehevo-opt-runtime-20261001/.env'
    values = dict(line.split('=', 1) for line in file.read_text(encoding='utf-8-sig').splitlines()
                  if '=' in line and not line.lstrip().startswith('#'))
    configured = values['APP_AI_CONFIG_ENCRYPTION_KEY'].strip()
    try:
        decoded = base64.b64decode(configured + '=' * ((-len(configured)) % 4), validate=True)
    except ValueError:
        decoded = b''
    key = decoded if len(decoded) == 32 else hashlib.sha256(configured.encode()).digest()
    # Same AES/GCM derivation as production ApiKeyEncryptionService. No raw DB/key output.
    records = query("""SELECT coalesce(json_agg(json_build_object(
      'nonce',api_key_nonce,'ciphertext',api_key_ciphertext)),'[]') FROM llm_provider_config;""")
    secrets = {AESGCM(key).decrypt(base64.b64decode(row['nonce']),
        base64.b64decode(row['ciphertext']), None) for row in records}
    secrets = sorted(value for value in secrets if len(value) > 16)
    assert secrets, 'Existing scoped API-key values required for scans'
    return secrets
