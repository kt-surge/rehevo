"""Back up/restore only the four public-document indexes in our isolated experiment DB.

Vector values are local-only, so the exact original UUID/index can be restored without
another embedding call. No provider/user tables or credentials are copied.
"""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import socket
import subprocess

from fact_gold import digest
from ingest_primary_dev import docker_json, write_json

ROOT = Path(__file__).resolve().parents[3]
RUN = ROOT / "observability/experiments/rag-evaluation/runs/structure-dev-20261001"
BASELINE = ROOT / "observability/experiments/rag-evaluation/runs/primary-dev-a-20261001"
SNAPSHOT = ROOT / "data/local/rehevo-opt-runtime-20261001/original-public-index.json"
PSQL = ["docker", "exec", "-i", "rehevo-opt-20261001-postgres-1", "psql",
        "-U", "postgres", "-d", "rehevo_opt", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1"]


def query(sql):
    return json.loads(subprocess.check_output(PSQL + ["-c", sql], encoding="utf-8"))


def current():
    return query("""
        SELECT json_build_object('database',current_database(),
          'knowledgeBases',(SELECT json_agg(row_to_json(k) ORDER BY id) FROM
            (SELECT id,file_hash,category,chunk_count,vector_status,vector_error FROM knowledge_bases WHERE id IN (1,2,3,4)) k),
          'vectors',(SELECT json_agg(row_to_json(v) ORDER BY id) FROM
            (SELECT id::text,content,metadata,embedding::text FROM vector_store WHERE metadata->>'kb_id' IN ('1','2','3','4')) v),
          'pendingVectors',(SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' IN ('1','2','3','4')));
        """)


def assert_scope(value):
    if value["database"] != "rehevo_opt" or value["pendingVectors"] != 0:
        raise ValueError("Wrong database or unfinished vector job; no restore allowed")
    ingestion = json.loads((BASELINE / "ingestion.manifest.json").read_text(encoding="utf-8"))
    expected = {row["knowledgeBaseId"]: row["uploadedCanonicalTextSha256"] for row in ingestion["documents"]}
    if set(expected) != {1, 2, 3, 4}:
        raise ValueError("Unexpected experiment knowledge-base IDs")
    actual = {row["id"]: row["file_hash"] for row in value["knowledgeBases"]}
    if actual != expected or any(row["category"] != "primary-fact-dev-20261001" or row["vector_status"] != "COMPLETED"
                                 for row in value["knowledgeBases"]):
        raise ValueError("Input documents/status changed; do not overwrite")
    pending = docker_json("redis", "redis-cli", "--json", "XPENDING", "knowledgebase:vectorize:stream", "vectorize-group")
    if pending[0] != 0:
        raise ValueError("Vector consumer has pending messages; wait for terminal state")


def vectors_hash(value):
    return hashlib.sha256(json.dumps(value["vectors"], ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("backup", "restore"))
    args = parser.parse_args()
    state = current()
    assert_scope(state)
    if args.action == "backup":
        if SNAPSHOT.exists():
            raise ValueError("Original index snapshot exists; do not replace")
        expected_ids = {row["chunk_id"] for row in json.loads((BASELINE / "actual-chunks.json").read_text(encoding="utf-8"))}
        if {row["id"] for row in state["vectors"]} != expected_ids:
            raise ValueError("Index no longer matches original actual baseline IDs")
        if subprocess.run(["git", "check-ignore", "--quiet", str(SNAPSHOT)], cwd=ROOT).returncode != 0:
            raise ValueError("Local vector snapshot must be ignored by Git")
        write_json(SNAPSHOT, state)
        manifest = {"database": "rehevo_opt", "publicKnowledgeBaseIds": [1, 2, 3, 4],
                    "vectorRows": len(state["vectors"]), "snapshotSha256": digest(SNAPSHOT),
                    "vectorsSha256": vectors_hash(state), "localSnapshotPath": str(SNAPSHOT.relative_to(ROOT)),
                    "providerTablesCopied": False, "credentialValuesRecorded": False}
        write_json(RUN / "index-backup-manifest.json", manifest)
        print(json.dumps({"action": "backup", "vectorRows": manifest["vectorRows"], "credentialsRecorded": False}))
        return
    with socket.socket() as connection:
        connection.settimeout(2)
        if connection.connect_ex(("127.0.0.1", 18080)) == 0:
            raise ValueError("Stop the confirmed experiment application before restoring its index")
    manifest = json.loads((RUN / "index-backup-manifest.json").read_text(encoding="utf-8"))
    if digest(SNAPSHOT) != manifest["snapshotSha256"]:
        raise ValueError("Original snapshot fingerprint changed")
    original = json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    assert_scope(original)
    payload = io.StringIO()
    csv.writer(payload, lineterminator="\n").writerow([json.dumps(original, ensure_ascii=False)])
    sql = """
        BEGIN;
        CREATE TEMP TABLE rehevo_public_index_restore(payload jsonb) ON COMMIT DROP;
        COPY rehevo_public_index_restore FROM STDIN WITH (FORMAT csv);
        """ + payload.getvalue() + "\\.\n" + """
        DELETE FROM vector_store WHERE metadata->>'kb_id' IN ('1','2','3','4');
        INSERT INTO vector_store(id,content,metadata,embedding)
          SELECT (v->>'id')::uuid,v->>'content',(v->'metadata')::json,(v->>'embedding')::vector
          FROM rehevo_public_index_restore,jsonb_array_elements(payload->'vectors') v;
        UPDATE knowledge_bases k SET chunk_count=(b->>'chunk_count')::int,
          vector_status=b->>'vector_status',vector_error=b->>'vector_error'
          FROM rehevo_public_index_restore,jsonb_array_elements(payload->'knowledgeBases') b
          WHERE k.id=(b->>'id')::bigint;
        COMMIT;
        """
    # Argument list + stdin preserves JSON/newlines without shell interpolation.
    result = subprocess.run(PSQL, input=sql, encoding="utf-8", capture_output=True)
    if result.returncode != 0:
        raise ValueError("Index restore transaction failed: " + result.stderr)
    restored = current()
    assert_scope(restored)
    if vectors_hash(restored) != manifest["vectorsSha256"]:
        raise ValueError("Restored vector IDs/content/metadata/values do not match original snapshot")
    write_json(RUN / "index-restore-verification.json", {"restoredRows": len(restored["vectors"]),
               "sameOriginalVectorFingerprint": True, "providerTablesModified": False})
    print(json.dumps({"action": "restore", "vectorRows": len(restored["vectors"]), "originalFingerprintMatches": True}))


if __name__ == "__main__":
    main()
