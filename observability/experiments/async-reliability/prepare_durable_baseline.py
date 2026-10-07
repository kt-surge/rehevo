"""Rebuild old production sources in an ignored copy, with the current identical recovery requirement test."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[3]
RUN = Path(__file__).parent / "runs/durable-delivery-20261002"
BASELINE = ROOT / "data/local/durable-delivery-baseline-20261002"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    if BASELINE.exists():
        raise ValueError("Baseline copy exists; do not overwrite")
    if subprocess.run(["git", "check-ignore", "--quiet", str(BASELINE / "app/build.gradle")], cwd=ROOT).returncode != 0:
        raise ValueError("Source copy must remain ignored")
    BASELINE.mkdir()
    for path in ("settings.gradle", "gradlew", "gradlew.bat", "AGENTS.md"):
        shutil.copyfile(ROOT / path, BASELINE / path)
    shutil.copytree(ROOT / "gradle", BASELINE / "gradle")
    (BASELINE / "app").mkdir()
    shutil.copyfile(ROOT / "app/build.gradle", BASELINE / "app/build.gradle")
    shutil.copytree(ROOT / "app/src", BASELINE / "app/src")
    index = json.loads((RUN / "old-sources/file-index.json").read_text(encoding="utf-8"))
    restored = []
    for original, frozen in index.items():
        if original.endswith(("VectorTaskDeliveryRecoveryIntegrationTest.java", "VectorTaskEnqueueCrashWorker.java")):
            continue  # Corrected harness stays identical in A and B; production baseline stays frozen.
        shutil.copyfile(RUN / "old-sources" / frozen, BASELINE / original)
        restored.append(original)
    prior_producer = ROOT / "observability/experiments/async-reliability/runs/vector-generation-20261001/candidate-sources"
    prior_index = json.loads((prior_producer / "file-index.json").read_text(encoding="utf-8"))
    producer_path = "app/src/main/java/interview/guide/common/async/AbstractStreamProducer.java"
    # The previous candidate snapshot may omit this unchanged template. The old void body is reconstructed
    # only when the snapshot lacks it; removal is exactly the two return statements/signature changed in D1.
    if producer_path in prior_index:
        shutil.copyfile(prior_producer / prior_index[producer_path], BASELINE / producer_path)
        producer_provenance = "prior frozen unchanged producer"
    else:
        content = (ROOT / producer_path).read_text(encoding="utf-8")
        content = content.replace("protected String sendTask(T payload)", "protected void sendTask(T payload)")
        content = content.replace("            return messageId;\n", "").replace(
            "applicationMetrics.recordStreamEnqueued(streamKey(), ApplicationMetrics.Outcome.FAILURE);\n            return null;\n",
            "applicationMetrics.recordStreamEnqueued(streamKey(), ApplicationMetrics.Outcome.FAILURE);\n")
        tracked = subprocess.check_output(["git", "show", "HEAD:" + producer_path], cwd=ROOT)
        if content.replace("\r\n", "\n") != tracked.decode("utf-8").replace("\r\n", "\n"):
            raise ValueError("Old producer was not unchanged tracked source; cannot infer the baseline")
        (BASELINE / producer_path).write_bytes(tracked)
        producer_provenance = "tracked unchanged HEAD template verified against the exact inverse of only D1 return changes"
    for name in ("common/config/VectorTaskRecoveryProperties.java", "modules/knowledgebase/model/VectorTaskInput.java",
                 "modules/knowledgebase/model/VectorTaskEntity.java", "modules/knowledgebase/model/VectorTaskDeliveryDTO.java",
                 "modules/knowledgebase/repository/VectorTaskRepository.java",
                 "modules/knowledgebase/service/VectorTaskRecoveryService.java"):
        path = BASELINE / "app/src/main/java/interview/guide" / name
        if not path.resolve().is_relative_to(BASELINE.resolve()):
            raise ValueError("Unsafe baseline source path")
        path.unlink()
    files = {path.relative_to(BASELINE).as_posix(): digest(path) for path in sorted(BASELINE.rglob("*")) if path.is_file()}
    manifest = {"sourceRoot": str(BASELINE), "credentialsCopied": False, "gitRepositoryCopied": False,
                "restoredFrozenProductionPaths": restored, "oldProducerProvenance": producer_provenance,
                "currentRecoveryHarnessSha256": digest(ROOT / "app/src/test/java/interview/guide/modules/knowledgebase/listener/VectorTaskDeliveryRecoveryIntegrationTest.java"),
                "files": files}
    (RUN / "baseline-copy.manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"sourceFilesCopied": len(files), "credentialsCopied": False}))


if __name__ == "__main__":
    main()
