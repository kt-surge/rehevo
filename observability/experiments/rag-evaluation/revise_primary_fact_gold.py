"""Freeze a transparent annotation correction before any primary-dev quality score.

Three source facts are documentation prose, not executable code/string literals.
No source spans, questions, conditions or answers change. Original r1 stays frozen.
"""
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil

from fact_gold import digest, load_gold

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "data/local/fact-gold-v1-20261001-r1"
TARGET = ROOT / "data/local/fact-gold-v1-20261001-r2"
PROSE_FACTS = {"pg-rc-two-selects", "tg-word-threshold", "sp-annotation-activation"}


def main():
    load_gold(SOURCE / "manifest.json", "agent_verified")
    if TARGET.exists():
        raise ValueError("Revision exists; do not overwrite")
    shutil.copytree(SOURCE, TARGET)
    facts = [json.loads(line) for line in (TARGET / "facts.jsonl").read_text(encoding="utf-8").splitlines()]
    for fact in facts:
        if fact["factId"] in PROSE_FACTS:
            fact.update(textRole="documentation_prose", allowInterChunkWhitespaceGap=True,
                        evidenceReviewNote="Original fact text is prose. Inter-chunk whitespace is formatting; all conditions/identifiers must remain exact. Same-Agent source review.")
    (TARGET / "facts.jsonl").write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in facts), encoding="utf-8")
    manifest = json.loads((TARGET / "manifest.json").read_text(encoding="utf-8"))
    manifest.update(packId="primary-doc-facts-dev-20261001-r2", factsSha256=digest(TARGET / "facts.jsonl"),
                    frozenAt=datetime.now(timezone.utc).isoformat(), supersedesManifestSha256=digest(SOURCE / "manifest.json"),
                    revisionReason="Pre-score corpus alignment found three formatting-only false negatives. Explicit prose annotation permits only inter-chunk whitespace gaps; raw source, fact spans, questions and references remain unchanged.",
                    noPrimaryDevQualityScoresExistedAtRevision=True)
    (TARGET / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    # The copied validation describes r1; preserve it under that name and create a current validation.
    old = TARGET / "validation.json"
    if old.exists():
        old.rename(TARGET / "previous-r1-validation.json")
    gold = load_gold(TARGET / "manifest.json", "agent_verified")
    summary = {"goldManifestSha256": digest(gold.manifest_path), "facts": len(gold.facts),
               "cases": len(gold.cases), "proseWhitespaceAnnotations": sorted(PROSE_FACTS),
               "humanReviewed": False, "testMaterialsPrepared": False}
    (TARGET / "validation.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary))


if __name__ == "__main__":
    main()
