"""事实评测契约回归；所有样本是测试 Fixture，不是质量评测结果。"""

import copy
import hashlib
import json
import tempfile
import unittest
from types import SimpleNamespace
from pathlib import Path

from fact_gold import ContractError, covered_fact_ids, digest, load_gold, load_variant, score


class FactGoldContractTest(unittest.TestCase):
  def setUp(self):
    self.temp = tempfile.TemporaryDirectory(prefix="rehevo-fact-contract-")
    self.addCleanup(self.temp.cleanup)
    self.root = Path(self.temp.name).resolve()
    self.source = "配置值=8080。生效条件=重启。汇总=8080且重启。"
    self.write_text("doc.txt", self.source)
    self.doc = {
        "documentId": "doc", "rawPath": "doc.txt", "canonicalPath": "doc.txt",
        "documentSha256": digest(self.root / "doc.txt"),
        "sourceTextSha256": digest(self.root / "doc.txt"),
        "sourceType": "controlled_fixture", "parserVersion": "test-v1",
        "topicGroup": "config", "split": "dev"}
    self.facts = []
    for fact_id, quote in (("value", "8080"), ("condition", "重启"), ("summary", "8080且重启")):
      start = self.source.index(quote)
      self.facts.append({"factId": fact_id, "documentId": "doc", "start": start,
          "end": start + len(quote), "exactQuote": quote})
    self.case = {"id": "case", "split": "dev", "question": "配置值和生效条件是什么？",
        "category": "condition", "answerable": True, "sourceDocumentIds": ["doc"],
        "requirements": [{"id": "complete", "alternatives": [
            {"factIds": ["value", "condition"]}, {"factIds": ["summary"]}]}],
        "referenceAnswer": "配置值8080，重启生效。", "referenceFactIds": ["value", "condition"],
        "review": {"status": "agent_verified", "reviewerType": "agent", "reviewer": "test-fixture",
            "reviewedAt": "2026-10-01T07:00:00Z"}}
    self.cases = [self.case]
    self.manifest = {"schemaVersion": "rehevo-fact-gold-v1", "documents": [self.doc],
        "factsPath": "facts.jsonl", "splits": {"dev": {"path": "dev.jsonl"}}}
    self.refresh()

  def write_text(self, name, value):
    (self.root / name).write_text(value, encoding="utf-8")

  def write_json(self, name, value):
    self.write_text(name, json.dumps(value, ensure_ascii=False))

  def refresh(self):
    for name, rows in (("facts.jsonl", self.facts), ("dev.jsonl", self.cases)):
      self.write_text(name, "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows))
    self.manifest["factsSha256"] = digest(self.root / "facts.jsonl")
    self.manifest["splits"]["dev"].update(sha256=digest(self.root / "dev.jsonl"),
        cases=len(self.cases), answerable=sum(row["answerable"] for row in self.cases))
    self.write_json("manifest.json", self.manifest)

  def gold(self, minimum="agent_verified"):
    return load_gold(self.root / "manifest.json", minimum)

  def chunks(self, spans):
    chunks = []
    for index, (start, end) in enumerate(spans):
      chunks.append({"chunkId": str(index), "documentId": "doc", "text": self.source[start:end],
          "documentSha256": self.doc["documentSha256"], "sourceTextSha256": self.doc["sourceTextSha256"],
          "spans": [{"sourceStart": start, "sourceEnd": end, "chunkStart": 0, "chunkEnd": end - start}]})
    self.write_json("variant.json", {"goldManifestSha256": digest(self.root / "manifest.json"),
        "strategyFingerprint": hashlib.sha256(b"test-fixture").hexdigest(), "chunks": chunks})
    return load_variant(self.root / "variant.json", self.gold())

  def test_valid_contract_accepts_alternative_evidence(self):
    gold = self.gold()
    fact = gold.facts["summary"]
    chunks = self.chunks([(fact["start"], fact["end"])])
    result = score(gold, chunks, [{"caseId": "case", "status": "success",
        "candidateChunkIds": ["0"], "contextChunkIds": ["0"], "elapsedMs": 10}], "dev")
    self.assertEqual(result["contextAllRequiredRecall"], 1)
    self.assertFalse(result["humanReviewed"])

  def test_missing_condition_is_not_counted_as_complete(self):
    value = self.gold().facts["value"]
    chunks = self.chunks([(value["start"], value["end"])])
    result = score(self.gold(), chunks, [{"caseId": "case", "status": "success",
        "candidateChunkIds": ["0"], "contextChunkIds": ["0"], "elapsedMs": 10}], "dev")
    self.assertEqual(result["contextAllRequiredRecall"], 0)

  def test_reference_facts_must_cover_condition(self):
    self.case["referenceFactIds"] = ["value"]
    self.refresh()
    with self.assertRaisesRegex(ContractError, "未覆盖所有必要条件"):
      self.gold()

  def test_requirement_rank_counts_joint_evidence_and_context_loss(self):
    gold = self.gold()
    value, condition = gold.facts["value"], gold.facts["condition"]
    chunks = self.chunks([(value["start"], value["end"]), (condition["start"], condition["end"])])
    result = score(gold, chunks, [{"caseId": "case", "status": "success",
        "candidateChunkIds": ["0", "1"], "contextChunkIds": ["0"], "elapsedMs": 10}], "dev")
    self.assertEqual(result["rows"][0]["requirementResults"], [{"requirementId": "complete",
        "candidateSatisfied": True, "contextSatisfied": False, "firstSatisfiedRank": 2}])

  def test_non_object_manifest_rows_fail_with_contract_error(self):
    self.manifest["documents"] = ["not-a-document"]
    self.refresh()
    with self.assertRaisesRegex(ContractError, "每项应为对象"):
      self.gold()

  def test_review_timestamp_requires_timezone(self):
    self.case["review"]["reviewedAt"] = "2026-10-01T07:00:00"
    self.refresh()
    with self.assertRaisesRegex(ContractError, "缺少时区"):
      self.gold()

  def test_macro_and_requirement_micro_denominators_are_explicit(self):
    second = copy.deepcopy(self.case)
    self.case["requirements"] = [{"id": "value", "alternatives": [{"factIds": ["value"]}]}]
    self.case["referenceFactIds"] = ["value"]
    second["id"] = "second"
    second["requirements"] = [{"id": name, "alternatives": [{"factIds": [name]}]}
                              for name in ("value", "condition")]
    self.cases = [self.case, second]
    self.refresh()
    gold = self.gold()
    value = gold.facts["value"]
    chunks = self.chunks([(value["start"], value["end"])])
    records = [{"caseId": name, "status": "success", "candidateChunkIds": ["0"],
                "contextChunkIds": ["0"], "elapsedMs": 10} for name in ("case", "second")]
    result = score(gold, chunks, records, "dev")
    self.assertEqual(result["contextRequirementCoverage"], .75)
    self.assertAlmostEqual(result["contextRequirementMicroCoverage"], 2 / 3)
    self.assertEqual(result["requiredConditions"], 3)

  def test_wrong_quote_is_rejected_even_with_same_document(self):
    self.facts[0]["exactQuote"] = "9090"
    self.refresh()
    with self.assertRaisesRegex(ContractError, "引文不等于"):
      self.gold()

  def test_tampered_source_is_rejected(self):
    self.write_text("doc.txt", self.source + "后加内容")
    with self.assertRaisesRegex(ContractError, "指纹|不匹配"):
      self.gold()

  def test_agent_review_does_not_satisfy_human_gate(self):
    with self.assertRaisesRegex(ContractError, "尚未达到 human_verified"):
      self.gold("human_verified")

  def test_forged_review_type_is_rejected(self):
    self.case["review"]["reviewerType"] = "human"
    self.refresh()
    with self.assertRaisesRegex(ContractError, "主体不符"):
      self.gold()

  def test_contiguous_chunks_can_jointly_cover_a_fact(self):
    value = self.gold().facts["value"]
    start, end = value["start"], value["end"]
    chunks = self.chunks([(start, start + 2), (start + 2, end)])
    self.assertNotIn("value", covered_fact_ids(self.gold(), chunks, ["0"]))
    self.assertIn("value", covered_fact_ids(self.gold(), chunks, ["0", "1"]))

  def test_gap_between_chunks_is_not_joint_support(self):
    value = self.gold().facts["value"]
    start, end = value["start"], value["end"]
    chunks = self.chunks([(start, start + 1), (start + 2, end)])
    self.assertNotIn("value", covered_fact_ids(self.gold(), chunks, ["0", "1"]))

  def test_source_range_must_match_actual_chunk_text(self):
    self.chunks([(0, 8)])
    variant = json.loads((self.root / "variant.json").read_text(encoding="utf-8"))
    variant["chunks"][0]["text"] = "伪造内容不证明任何原文"
    self.write_json("variant.json", variant)
    with self.assertRaisesRegex(ContractError, "不支持原文映射"):
      load_variant(self.root / "variant.json", self.gold())

  def test_candidate_hit_and_final_context_are_scored_separately(self):
    gold = self.gold()
    chunks = self.chunks([(0, len(self.source))])
    result = score(gold, chunks, [{"caseId": "case", "status": "success",
        "candidateChunkIds": ["0"], "contextChunkIds": [], "elapsedMs": 20}], "dev")
    self.assertEqual(result["candidateAllRequiredRecall"], 1)
    self.assertEqual(result["contextAllRequiredRecall"], 0)

  def test_missing_and_failed_requests_stay_in_denominator(self):
    self.cases.append({**copy.deepcopy(self.case), "id": "missing-case"})
    self.refresh()
    chunks = self.chunks([(0, len(self.source))])
    result = score(self.gold(), chunks, [{"caseId": "case", "status": "failure",
        "candidateChunkIds": ["0"], "contextChunkIds": ["0"], "elapsedMs": 300}], "dev")
    self.assertEqual(result["cases"], 2)
    self.assertEqual(result["missing"], 1)
    self.assertEqual(result["failures"], 1)
    self.assertEqual(result["contextAllRequiredRecall"], 0)

  def test_unanswerable_nonempty_retrieval_is_not_refusal_accuracy(self):
    self.case.update(answerable=False, requirements=[], referenceFactIds=[],
        referenceAnswer="没有提供生产配置。", unanswerableReason="缺少生产配置")
    self.refresh()
    chunks = self.chunks([(0, len(self.source))])
    result = score(self.gold(), chunks, [{"caseId": "case", "status": "success",
        "candidateChunkIds": ["0"], "contextChunkIds": ["0"], "elapsedMs": 20}], "dev")
    self.assertIsNone(result["contextAllRequiredRecall"])
    self.assertIsNone(result["rows"][0]["contextAllRequired"])

  def test_unknown_chunk_is_rejected(self):
    chunks = self.chunks([(0, len(self.source))])
    with self.assertRaisesRegex(ContractError, "未知 Chunk"):
      score(self.gold(), chunks, [{"caseId": "case", "status": "success",
          "candidateChunkIds": ["999"], "contextChunkIds": [], "elapsedMs": 20}], "dev")

  def test_topic_and_source_isolation_prevent_split_leakage(self):
    other = {**self.doc, "documentId": "test-doc", "split": "test"}
    self.manifest["documents"].append(other)
    self.refresh()
    with self.assertRaisesRegex(ContractError, "跨 dev/test"):
      self.gold()

  def test_document_path_must_stay_in_package(self):
    self.doc["rawPath"] = "../outside.txt"
    self.refresh()
    with self.assertRaisesRegex(ContractError, "越界"):
      self.gold()

  def whitespace_gap_fixture(self, missing="\n", same_chunk=False, allowed=True):
    source = "条件A" + missing + "条件B"
    end_a = 3
    start_b = end_a + len(missing)
    fact = {"factId": "prose", "documentId": "doc", "start": 0, "end": len(source),
            "textRole": "documentation_prose", "allowInterChunkWhitespaceGap": allowed}
    gold = SimpleNamespace(source_texts={"doc": source}, facts={"prose": fact})
    spans = [{"sourceStart": 0, "sourceEnd": end_a}, {"sourceStart": start_b, "sourceEnd": len(source)}]
    if same_chunk:
      chunks = {"a": {"documentId": "doc", "spans": spans}}
    else:
      chunks = {"a": {"documentId": "doc", "spans": spans[:1]},
                "b": {"documentId": "doc", "spans": spans[1:]}}
    return covered_fact_ids(gold, chunks, list(chunks))

  def test_reviewed_prose_can_bridge_inter_chunk_whitespace(self):
    self.assertEqual(self.whitespace_gap_fixture(), {"prose"})

  def test_whitespace_bridge_cannot_hide_a_missing_condition(self):
    self.assertEqual(self.whitespace_gap_fixture(missing="且不得 "), set())

  def test_whitespace_deleted_within_a_chunk_is_not_bridged(self):
    self.assertEqual(self.whitespace_gap_fixture(same_chunk=True), set())

  def test_unannotated_literal_fact_keeps_strict_whitespace_requirement(self):
    self.assertEqual(self.whitespace_gap_fixture(allowed=False), set())

  def test_code_cannot_opt_into_prose_whitespace_policy(self):
    self.facts[0].update(allowInterChunkWhitespaceGap=True, textRole="code")
    self.refresh()
    with self.assertRaisesRegex(ContractError, "只有明确复核"):
      self.gold()


if __name__ == "__main__":
  unittest.main(verbosity=2)
