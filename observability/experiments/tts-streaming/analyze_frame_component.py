"""Integrity and descriptive analysis; no browser or formal percentile claim."""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np


def analyze(run):
    report = json.loads((run / "results.json").read_text(encoding="utf-8-sig"))
    manifest = json.loads((run / "source-manifest.json").read_text(encoding="utf-8-sig"))
    errors = []
    for name, item in manifest["sources"].items():
        actual = hashlib.sha256((run / "sources" / item["file"]).read_bytes()).hexdigest()
        if actual != item["sha256"]:
            errors.append(f"frozen source mismatch: {name}")
    pairs = {}
    usage_totals = {arm: dict(input_tokens=0, output_tokens=0, total_tokens=0) for arm in ("whole", "frames")}
    for row in report["results"]:
        audio = (run / row["audioPath"]).read_bytes()
        if len(audio) != row["bytes"] or hashlib.sha256(audio).hexdigest() != row["audioSha256"]:
            errors.append(f"audio integrity: {row['audioPath']}")
        if row["status"] != "success" or not audio or len(audio) % 2:
            errors.append(f"request or alignment failure: {row['audioPath']}")
        if sum(frame["bytes"] for frame in row["frames"]) != len(audio):
            errors.append(f"frame byte count: {row['audioPath']}")
        usage = row["sdkObservation"]["rawProviderUsageEvents"]
        distinct = {tuple(event.get(key) for key in ("requestId", "input_tokens", "output_tokens", "total_tokens")) for event in usage}
        if len(distinct) != 1 or any(v is None for v in next(iter(distinct), (None,))):
            errors.append(f"missing or ambiguous usage: {row['audioPath']}")
        else:
            values = next(iter(distinct))
            for key, value in zip(usage_totals[row["mode"]], values[1:]):
                usage_totals[row["mode"]][key] += value
        pairs.setdefault(row["pair"], {})[row["mode"]] = row
    identical = 0
    for pair, arms in pairs.items():
        if set(arms) != {"whole", "frames"} or arms["whole"]["textSha256"] != arms["frames"]["textSha256"]:
            errors.append(f"incomplete or unpaired: {pair}")
            continue
        identical += arms["whole"]["audioSha256"] == arms["frames"]["audioSha256"]
    stats = {}
    for arm in ("whole", "frames"):
        values = [row["firstDeliveredPcmMs"] for row in report["results"] if row["mode"] == arm and row["status"] == "success"]
        stats[arm] = dict(n=len(values), medianFirstDeliveredPcmMs=float(np.median(values)),
                          minFirstDeliveredPcmMs=min(values), maxFirstDeliveredPcmMs=max(values))
    result = dict(scope="diagnostic component only; no formal P95, browser or listening claim",
                  errors=errors, requestCount=len(report["results"]), pairs=len(pairs),
                  bitIdenticalAudioPairs=identical, usageTotalsDeduplicated=usage_totals,
                  usageRule="one distinct usage tuple per request; identical repeated events counted once; ambiguous events fail",
                  firstDeliveredPcm=stats)
    result["medianDeltaPercent"] = (stats["frames"]["medianFirstDeliveredPcmMs"] / stats["whole"]["medianFirstDeliveredPcmMs"] - 1) * 100
    (run / "analysis.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return len(errors) > 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("run", type=Path)
    raise SystemExit(analyze(parser.parse_args().run))
