"""Audit real ordered pipeline preflight, including startup priority and PCM parity."""
import argparse
import hashlib
import json
from pathlib import Path


def analyze(run, reference):
    report = json.loads((run / "results.json").read_text(encoding="utf-8-sig"))
    baseline = json.loads((reference / "results.json").read_text(encoding="utf-8-sig"))
    reference_hashes = {row["text"]: row["audioSha256"] for row in baseline["results"] if row["mode"] == "whole"}
    errors, findings = [], []
    for row in report["results"]:
        events = row["events"]
        per_sentence = {}
        sequence = [(e["sentenceIndex"], e["frameIndex"]) for e in events]
        if sequence != sorted(sequence) or len(set(sequence)) != len(sequence):
            errors.append(f"invalid output sequence: {row['scenario']}")
        for event in events:
            per_sentence.setdefault(event["sentenceIndex"], []).append(event)
        for item in row["audio"]:
            pcm = (run / item["file"]).read_bytes()
            if hashlib.sha256(pcm).hexdigest() != item["sha256"] or len(pcm) != item["bytes"] or len(pcm) % 2:
                errors.append(f"PCM integrity: {item['file']}")
            if sum(e["bytes"] for e in per_sentence.get(item["sentenceIndex"], [])) != len(pcm):
                errors.append(f"PCM frame total: {item['file']}")
            if row["scenario"] == "normal" and (not pcm or item["sha256"] != reference_hashes[item["text"]]):
                errors.append(f"complete audio differs from frozen whole reference: {item['file']}")
        if row["scenario"] == "normal":
            if row["terminal"] != "success" or any(sum(e["endOfSentence"] for e in group) != 1 for group in per_sentence.values()):
                errors.append("normal terminal or sentence end invalid")
        elif row["scenario"] == "cancel-after-first":
            if not row["firstReceivedBeforeCancel"] or len(events) != row["eventsAtClose"] or row["terminal"] != "failed":
                errors.append("cancel terminal or late events invalid")
        elif row["scenario"] == "overflow" and (events or row["terminal"] != "failed"):
            errors.append("overflow silently emitted or succeeded")
        sdk_calls = [o for o in row["sdkObservations"] if o["sdk"]["providerInvocations"] > 0]
        if row["scenario"] != "normal" and sdk_calls and sdk_calls[0]["text"] != row["audio"][0]["text"]:
            findings.append(f"single permit admitted later sentence first: {row['scenario']}")
    manifest = json.loads((run / "source-manifest.json").read_text(encoding="utf-8-sig"))
    for name, item in manifest["sources"].items():
        if hashlib.sha256((run / "sources" / item["file"]).read_bytes()).hexdigest() != item["sha256"]:
            errors.append(f"source integrity: {name}")
    result = dict(scope="real provider ordered pipeline diagnostics; no browser/P95 claim", errors=errors,
                  startupPriorityFindings=findings, normalAudioReference=str(reference),
                  sdkInvocationsByScenario={r["scenario"]: sum(o["sdk"]["providerInvocations"] for o in r["sdkObservations"]) for r in report["results"]},
                  cancelledUsage="unknown where terminal usage absent; not zero", scenarioCount=len(report["results"]))
    (run / "analysis.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return bool(errors)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=Path)
    parser.add_argument("reference", type=Path)
    args = parser.parse_args()
    raise SystemExit(analyze(args.run, args.reference))
