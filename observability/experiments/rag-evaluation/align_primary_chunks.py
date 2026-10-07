"""Strictly align actual ingested chunks to the frozen original canonical text.

Only equal character runs are mapped. Cleaning deletions stay gaps; no approximate match
or paraphrase is credited as evidence. The official HTML, upload bytes and parsed text
have separate fingerprints.
"""
import argparse
import difflib
import hashlib
import json
from pathlib import Path

from fact_gold import covered_fact_ids, digest, load_gold, load_variant


def codepoint_offset(text, offset):
    """Convert Java UTF-16 positions without accepting a half surrogate."""
    encoded = text.encode("utf-16-le")
    if not isinstance(offset, int) or offset < 0 or offset * 2 > len(encoded):
        raise ValueError("Invalid UTF-16 source position")
    return len(encoded[:offset * 2].decode("utf-16-le", errors="strict"))


def chunk_parts(row, parsed, cursor):
    text = row["text"]
    metadata = row.get("metadata", {})
    if metadata.get("chunking_mode") == "STRUCTURED":
        if metadata.get("source_offset_unit") != "UTF16" or not metadata.get("source_spans"):
            raise ValueError("Structured chunk has no explicit UTF-16 mapping")
        parts, covered = [], 0
        for span in metadata["source_spans"]:
            left = codepoint_offset(parsed, span["source_start"])
            right = codepoint_offset(parsed, span["source_end"])
            chunk_left = codepoint_offset(text, span["chunk_start"])
            chunk_right = codepoint_offset(text, span["chunk_end"])
            if left >= right or chunk_left != covered or chunk_left >= chunk_right:
                raise ValueError("Source spans must cover the actual chunk in order")
            if parsed[left:right] != text[chunk_left:chunk_right]:
                raise ValueError("Structured source mapping does not match actual text")
            parts.append((left, right, chunk_left, chunk_right))
            covered = chunk_right
        if covered != len(text):
            raise ValueError("Source spans omitted chunk characters")
        return parts, cursor
    start = parsed.find(text, cursor)
    if start < 0:
        raise ValueError(f"Chunk {row['chunk_id']} is not an exact ordered substring of actual parsed text")
    return [(start, start + len(text), 0, len(text))], start + len(text)


def source_spans(parts, equal_runs):
    spans = []
    for start, end, chunk_start, _ in parts:
        for source_start, source_end, parsed_start, parsed_end in equal_runs:
            left, right = max(start, parsed_start), min(end, parsed_end)
            if left < right:
                spans.append({"sourceStart": source_start + left - parsed_start,
                              "sourceEnd": source_start + right - parsed_start,
                              "chunkStart": chunk_start + left - start,
                              "chunkEnd": chunk_start + right - start})
    return spans


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gold", type=Path, required=True)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--revision", default="", help="Explicit suffix to preserve prior alignment, e.g. -r2")
    args = parser.parse_args()
    gold = load_gold(args.gold, "agent_verified")
    run = args.run.resolve()
    if args.revision not in ("", "-r2"):
        raise ValueError("Unsupported revision suffix")
    output = run / f"baseline-chunk-variant{args.revision}.json"
    if output.exists():
        raise ValueError("Alignment exists; do not overwrite a frozen variant")
    manifest = json.loads((run / "ingestion.manifest.json").read_text(encoding="utf-8"))
    actual = json.loads((run / "actual-chunks.json").read_text(encoding="utf-8"))
    by_kb = {str(row["knowledgeBaseId"]): row for row in manifest["documents"]}
    maps, parsed_texts, changes, cursors = {}, {}, {}, {}
    for kb_id, row in by_kb.items():
        doc_id = row["documentId"]
        source = gold.source_texts[doc_id]
        parsed_path = run / row["parsedTextPath"]
        if digest(parsed_path) != row["actualParsedTextSha256"]:
            raise ValueError("Actual parsed text changed after export")
        parsed = parsed_path.read_bytes().decode("utf-8")
        parsed_texts[kb_id] = parsed
        matcher = difflib.SequenceMatcher(None, source, parsed, autojunk=False)
        exact_position = source.find(parsed)
        opcodes = matcher.get_opcodes() if exact_position < 0 else [
            ("delete", 0, exact_position, 0, 0),
            ("equal", exact_position, exact_position + len(parsed), 0, len(parsed)),
            ("delete", exact_position + len(parsed), len(source), len(parsed), len(parsed))]
        opcodes = [op for op in opcodes if op[1] != op[2] or op[3] != op[4]]
        maps[kb_id] = [(a, b, c, d) for op, a, b, c, d in opcodes if op == "equal"]
        changes[doc_id] = [{"operation": op, "sourceRange": [a, b], "parsedRange": [c, d],
                           "sourceText": source[a:b], "parsedText": parsed[c:d]}
                          for op, a, b, c, d in opcodes if op != "equal"]
        cursors[kb_id] = 0
    chunks = []
    for row in sorted(actual, key=lambda item: (int(item["kb_id"]), item["chunk_index"])):
        kb_id = row["kb_id"]
        provenance = by_kb[kb_id]
        doc_id = provenance["documentId"]
        doc = gold.documents[doc_id]
        text = row["text"]
        parts, cursors[kb_id] = chunk_parts(row, parsed_texts[kb_id], cursors[kb_id])
        spans = source_spans(parts, maps[kb_id])
        chunks.append({"chunkId": row["chunk_id"], "documentId": doc_id,
                       "knowledgeBaseId": int(kb_id), "chunkIndex": row["chunk_index"], "text": text,
                       "documentSha256": doc["documentSha256"], "sourceTextSha256": doc["sourceTextSha256"],
                       "actualUploadSha256": provenance["uploadedCanonicalTextSha256"],
                       "actualParsedTextSha256": provenance["actualParsedTextSha256"],
                       "actualParsedRanges": [[part[0], part[1]] for part in parts], "spans": spans})
    source_hashes = {key.replace("\\", "/"): value for key, value in
                     json.loads((run / "ingestion-source-hashes.json").read_text(encoding="utf-8")).items()}
    strategy = {"chunker": manifest.get("strategyInfo", {
                    "mode": "TOKEN", "implementation": "Spring AI 2.0.0 TokenTextSplitter.builder().build(); no overlap"}),
                "ingestionManifestSha256": digest(run / "ingestion.manifest.json"),
                "vectorServiceSha256": source_hashes[
                    "app/src/main/java/interview/guide/modules/knowledgebase/service/KnowledgeBaseVectorService.java"]}
    strategy["chunkingSourceHashes"] = {key: value for key, value in source_hashes.items()
                                        if key.endswith(("DocumentChunkingService.java", "StructureAwareDocumentSplitter.java",
                                                         "DocumentChunkingProperties.java"))}
    fingerprint = hashlib.sha256(json.dumps(strategy, sort_keys=True).encode("utf-8")).hexdigest()
    variant = {"goldManifestSha256": digest(gold.manifest_path), "strategyFingerprint": fingerprint,
               "strategy": strategy, "mappingMethod": "validated UTF16 source parts or legacy ordered substring + equal canonical/parsed runs; Gold offsets are codepoints",
               "chunks": chunks}
    output.write_text(json.dumps(variant, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    checked = load_variant(output, gold)
    covered = covered_fact_ids(gold, checked, list(checked))
    report = {"chunksValidated": len(checked), "factsInGold": len(gold.facts), "factsFullyPreserved": len(covered),
              "uncoveredFactIds": sorted(set(gold.facts) - covered), "parsingChanges": changes,
              "variantSha256": digest(output), "scope": "corpus preservation diagnostic, not retrieval score"}
    (run / f"alignment-report{args.revision}.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value for key, value in report.items() if key != "parsingChanges"}, ensure_ascii=False))


if __name__ == "__main__":
    main()
