#!/usr/bin/env python3
"""原文事实金标校验与检索评分。标准库实现，不调用模型，不推断人工审核通过。"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import statistics
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any


class ContractError(ValueError):
  pass


def require(condition: bool, message: str) -> None:
  if not condition:
    raise ContractError(message)


def digest(path: Path) -> str:
  return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path: Path) -> Any:
  return json.loads(path.read_bytes().decode("utf-8-sig"))


def read_jsonl(path: Path) -> list[dict]:
  rows = []
  for line_number, line in enumerate(path.read_bytes().decode("utf-8-sig").splitlines(), 1):
    if line.strip():
      row = json.loads(line)
      require(isinstance(row, dict), f"{path.name}:{line_number}: 对象格式错误")
      rows.append(row)
  return rows


def local_path(base: Path, relative: str) -> Path:
  require(isinstance(relative, str) and bool(relative), "缺少文件相对路径")
  target = (base / relative).resolve()
  require(target.is_relative_to(base.resolve()), f"文件路径越界: {relative}")
  require(target.is_file(), f"文件不存在: {relative}")
  return target


def unique_rows(rows: list[dict], field: str, name: str) -> dict[str, dict]:
  require(isinstance(rows, list), f"{name}: 应为数组")
  result = {}
  for row in rows:
    require(isinstance(row, dict), f"{name}: 每项应为对象")
    key = row.get(field)
    require(isinstance(key, str) and bool(key.strip()), f"{name}: 缺少 {field}")
    require(key not in result, f"{name}: 重复 {field}={key}")
    result[key] = row
  return result


def checked_ids(value: Any, name: str, nonempty: bool = True) -> list[str]:
  require(isinstance(value, list), f"{name}: 应为数组")
  require(all(isinstance(item, str) and item for item in value), f"{name}: 含非法 ID")
  require(len(value) == len(set(value)), f"{name}: ID 重复")
  require(bool(value) or not nonempty, f"{name}: 不得为空")
  return value


REVIEW_LEVELS = {"draft": 0, "agent_verified": 1, "human_verified": 2}
REVIEW_TYPES = {"draft": "none", "agent_verified": "agent", "human_verified": "human"}


def validate_review(review: Any, case_id: str, minimum: str) -> None:
  require(isinstance(review, dict), f"{case_id}: 缺少复核主体")
  status = review.get("status")
  require(status in REVIEW_LEVELS, f"{case_id}: 非法复核状态")
  require(review.get("reviewerType") == REVIEW_TYPES[status], f"{case_id}: 复核状态与主体不符")
  if status != "draft":
    require(bool(review.get("reviewer")), f"{case_id}: 缺少复核人/Agent 标识")
    try:
      reviewed_at = datetime.fromisoformat(review["reviewedAt"].replace("Z", "+00:00"))
    except (KeyError, TypeError, ValueError, AttributeError) as error:
      raise ContractError(f"{case_id}: 复核时间不合法") from error
    require(reviewed_at.utcoffset() is not None, f"{case_id}: 复核时间缺少时区")
  require(REVIEW_LEVELS[status] >= REVIEW_LEVELS[minimum], f"{case_id}: 尚未达到 {minimum}")


@dataclass
class Gold:
  manifest_path: Path
  manifest: dict
  documents: dict[str, dict]
  source_texts: dict[str, str]
  facts: dict[str, dict]
  cases: dict[str, dict]


def requirement_met(requirement: dict, covered_facts: set[str]) -> bool:
  return any(set(option["factIds"]) <= covered_facts for option in requirement["alternatives"])


def load_gold(manifest_path: Path, minimum_review: str = "draft") -> Gold:
  manifest_path = manifest_path.resolve()
  base = manifest_path.parent
  manifest = read_json(manifest_path)
  require(isinstance(manifest, dict), "清单应为对象")
  require(minimum_review in REVIEW_LEVELS, "最低复核状态非法")
  require(manifest.get("schemaVersion") == "rehevo-fact-gold-v1", "不支持的事实金标版本")
  documents = unique_rows(manifest.get("documents", []), "documentId", "documents")
  require(bool(documents), "文档清单为空")
  texts = {}
  topics: dict[str, str] = {}
  document_hashes: dict[str, str] = {}
  canonical_hashes: dict[str, str] = {}
  for doc_id, doc in documents.items():
    split = doc.get("split")
    require(split in ("dev", "test"), f"{doc_id}: split 非法")
    require(doc.get("sourceType") in (
        "user_material", "project_technical_material", "public_primary_document", "controlled_fixture"),
        f"{doc_id}: 来源类型非法")
    require(bool(doc.get("parserVersion")) and bool(doc.get("topicGroup")), f"{doc_id}: 缺少版本或主题组")
    topic = doc["topicGroup"]
    require(topics.get(topic, split) == split, f"{doc_id}: 主题组跨 dev/test 泄漏")
    topics[topic] = split
    for path_field, hash_field, seen in (
        ("rawPath", "documentSha256", document_hashes),
        ("canonicalPath", "sourceTextSha256", canonical_hashes)):
      path = local_path(base, doc.get(path_field))
      expected = doc.get(hash_field)
      require(digest(path) == expected, f"{doc_id}: {hash_field} 不匹配")
      require(seen.get(expected, split) == split, f"{doc_id}: 相同文档/规范文本跨 split")
      seen[expected] = split
    texts[doc_id] = local_path(base, doc["canonicalPath"]).read_bytes().decode("utf-8-sig")

  facts_path = local_path(base, manifest.get("factsPath"))
  require(digest(facts_path) == manifest.get("factsSha256"), "事实文件指纹不匹配")
  facts = unique_rows(read_jsonl(facts_path), "factId", "facts")
  for fact_id, fact in facts.items():
    doc_id = fact.get("documentId")
    require(doc_id in documents, f"{fact_id}: 未知文档")
    start, end = fact.get("start"), fact.get("end")
    require(type(start) is int and type(end) is int and 0 <= start < end <= len(texts[doc_id]),
        f"{fact_id}: 原文区间非法")
    require(texts[doc_id][start:end] == fact.get("exactQuote"), f"{fact_id}: 引文不等于原文区间")
    bridge = fact.get("allowInterChunkWhitespaceGap", False)
    require(type(bridge) is bool, f"{fact_id}: 空白边界策略应为布尔值")
    require(not bridge or fact.get("textRole") == "documentation_prose",
        f"{fact_id}: 只有明确复核的说明文字允许跨片段空白")

  all_cases = []
  splits = manifest.get("splits")
  require(isinstance(splits, dict) and bool(splits), "缺少 split 清单")
  for split, contract in splits.items():
    require(split in ("dev", "test"), "split 清单非法")
    require(isinstance(contract, dict), f"{split}: 清单应为对象")
    path = local_path(base, contract.get("path"))
    require(digest(path) == contract.get("sha256"), f"{split}: 题集指纹不匹配")
    cases = read_jsonl(path)
    require(len(cases) == contract.get("cases") and bool(cases), f"{split}: 题量不匹配/为空")
    require(sum(case.get("answerable") is True for case in cases) == contract.get("answerable"),
        f"{split}: 可回答题量不匹配")
    for case in cases:
      case_id = case.get("id", "<missing>")
      require(case.get("split") == split, f"{case_id}: split 与清单不符")
      require(isinstance(case.get("question"), str) and len(case["question"].strip()) >= 2,
          f"{case_id}: 问题为空")
      require(type(case.get("answerable")) is bool, f"{case_id}: answerable 应为布尔值")
      require(isinstance(case.get("referenceAnswer"), str) and bool(case["referenceAnswer"].strip()),
          f"{case_id}: 参考答案为空")
      doc_ids = checked_ids(case.get("sourceDocumentIds"), f"{case_id}.sourceDocumentIds")
      require(all(doc_id in documents and documents[doc_id]["split"] == split for doc_id in doc_ids),
          f"{case_id}: 来源跨 split 或不存在")
      requirements = case.get("requirements")
      require(isinstance(requirements, list), f"{case_id}: requirements 应为数组")
      unique_rows(requirements, "id", f"{case_id}.requirements")
      used_facts = set()
      for requirement in requirements:
        alternatives = requirement.get("alternatives")
        require(isinstance(alternatives, list) and bool(alternatives), f"{case_id}: 替代证据为空")
        seen_options = set()
        for option in alternatives:
          require(isinstance(option, dict), f"{case_id}: 替代证据应为对象")
          ids = checked_ids(option.get("factIds"), f"{case_id}.factIds")
          require(all(fact_id in facts and facts[fact_id]["documentId"] in doc_ids for fact_id in ids),
              f"{case_id}: 事实不存在或不在来源范围")
          key = frozenset(ids)
          require(key not in seen_options, f"{case_id}: 替代证据组重复")
          seen_options.add(key)
          used_facts.update(ids)
      reference_ids = set(checked_ids(case.get("referenceFactIds"),
          f"{case_id}.referenceFactIds", nonempty=case["answerable"]))
      require(reference_ids <= used_facts, f"{case_id}: 参考事实不在需求证据中")
      if case["answerable"]:
        require(bool(requirements), f"{case_id}: 可回答题没有必要条件")
        require(all(requirement_met(item, reference_ids) for item in requirements),
            f"{case_id}: 参考事实未覆盖所有必要条件")
      else:
        require(not requirements and not reference_ids, f"{case_id}: 不可回答题携带正向证据")
        require(bool(case.get("unanswerableReason")), f"{case_id}: 缺少不可回答理由")
      validate_review(case.get("review"), case_id, minimum_review)
    all_cases.extend(cases)
  cases_by_id = unique_rows(all_cases, "id", "cases")
  return Gold(manifest_path, manifest, documents, texts, facts, cases_by_id)


def load_variant(path: Path, gold: Gold) -> dict[str, dict]:
  variant = read_json(path)
  require(isinstance(variant, dict), "变体应为对象")
  require(variant.get("goldManifestSha256") == digest(gold.manifest_path), "变体引用的 Gold 指纹不符")
  require(isinstance(variant.get("strategyFingerprint"), str)
      and re.fullmatch(r"[a-f0-9]{64}", variant["strategyFingerprint"]) is not None,
      "变体策略指纹不是 SHA256")
  chunks = unique_rows(variant.get("chunks", []), "chunkId", "chunks")
  require(bool(chunks), "变体 Chunk 为空")
  for chunk_id, chunk in chunks.items():
    doc_id = chunk.get("documentId")
    require(doc_id in gold.documents, f"{chunk_id}: 未知来源文档")
    doc = gold.documents[doc_id]
    require(chunk.get("documentSha256") == doc["documentSha256"]
        and chunk.get("sourceTextSha256") == doc["sourceTextSha256"], f"{chunk_id}: 来源指纹不符")
    text = chunk.get("text")
    require(isinstance(text, str), f"{chunk_id}: 缺少实际文本")
    spans = chunk.get("spans")
    require(isinstance(spans, list), f"{chunk_id}: 缺少原文映射")
    for span in spans:
      require(isinstance(span, dict), f"{chunk_id}: 映射应为对象")
      ss, se, cs, ce = [span.get(key) for key in ("sourceStart", "sourceEnd", "chunkStart", "chunkEnd")]
      require(all(type(value) is int for value in (ss, se, cs, ce)), f"{chunk_id}: 映射区间不是整数")
      require(0 <= ss < se <= len(gold.source_texts[doc_id]) and 0 <= cs < ce <= len(text),
          f"{chunk_id}: 映射区间越界")
      require(gold.source_texts[doc_id][ss:se] == text[cs:ce], f"{chunk_id}: 实际文本不支持原文映射")
  return chunks


def covered_fact_ids(gold: Gold, chunks: dict[str, dict], selected: list[str]) -> set[str]:
  ranges: dict[str, list[tuple[int, int, str]]] = {}
  for chunk_id in selected:
    chunk = chunks[chunk_id]
    ranges.setdefault(chunk["documentId"], []).extend(
        (span["sourceStart"], span["sourceEnd"], chunk_id) for span in chunk["spans"])
  covered = set()
  for fact_id, fact in gold.facts.items():
    cursor = fact["start"]
    previous_chunk = None
    for start, end, chunk_id in sorted(ranges.get(fact["documentId"], [])):
      if start > cursor:
        gap = gold.source_texts[fact["documentId"]][cursor:start]
        if not (fact.get("allowInterChunkWhitespaceGap", False)
                and fact.get("textRole") == "documentation_prose"
                and previous_chunk is not None and previous_chunk != chunk_id and gap.isspace()):
          break
      if end > cursor:
        cursor = end
        previous_chunk = chunk_id
      if cursor >= fact["end"]:
        covered.add(fact_id)
        break
  return covered


def coverage(case: dict, covered: set[str]) -> tuple[float | None, bool | None]:
  if not case["answerable"]:
    return None, None
  satisfied = sum(requirement_met(item, covered) for item in case["requirements"])
  return satisfied / len(case["requirements"]), satisfied == len(case["requirements"])


def nearest_rank(values: list[float], fraction: float) -> float | None:
  return sorted(values)[math.ceil(len(values) * fraction) - 1] if values else None


def score(gold: Gold, chunks: dict[str, dict], records: list[dict], split: str) -> dict:
  cases = {key: case for key, case in gold.cases.items() if case["split"] == split}
  require(bool(cases), f"没有 {split} 样本")
  results = unique_rows(records, "caseId", "retrieval")
  require(set(results) <= set(cases), "检索记录含未知或另一 split 样本")
  rows = []
  for case_id, case in cases.items():
    record = results.get(case_id)
    row = {"caseId": case_id, "answerable": case["answerable"], "category": case.get("category", "unspecified")}
    candidates, context = [], []
    elapsed = None
    status = "missing"
    if record:
      status = record.get("status")
      require(status in ("success", "failure"), f"{case_id}: 检索状态非法")
      candidates = checked_ids(record.get("candidateChunkIds"), f"{case_id}.candidates", False)
      context = checked_ids(record.get("contextChunkIds"), f"{case_id}.context", False)
      require(all(chunk_id in chunks for chunk_id in candidates + context), f"{case_id}: 未知 Chunk")
      require(all(gold.documents[chunks[chunk_id]["documentId"]]["split"] == split
          for chunk_id in candidates + context), f"{case_id}: 检索到另一 split 资料")
      elapsed = record.get("elapsedMs")
      require(type(elapsed) in (int, float) and math.isfinite(elapsed) and elapsed >= 0,
          f"{case_id}: 缺少有效逐题耗时")
    available_candidates = candidates if status == "success" else []
    available_context = context if status == "success" else []
    candidate_coverage, candidate_all = coverage(case, covered_fact_ids(gold, chunks, available_candidates))
    context_coverage, context_all = coverage(case, covered_fact_ids(gold, chunks, available_context))
    candidate_facts = covered_fact_ids(gold, chunks, available_candidates)
    context_facts = covered_fact_ids(gold, chunks, available_context)
    requirement_results = []
    for requirement in case["requirements"]:
      first_satisfied_rank = next((rank for rank in range(1, len(available_candidates) + 1)
          if requirement_met(requirement,
              covered_fact_ids(gold, chunks, available_candidates[:rank]))), None)
      requirement_results.append({"requirementId": requirement["id"],
          "candidateSatisfied": requirement_met(requirement, candidate_facts),
          "contextSatisfied": requirement_met(requirement, context_facts),
          "firstSatisfiedRank": first_satisfied_rank})
    first_rank = None
    if case["answerable"] and status == "success":
      for rank in range(1, len(candidates) + 1):
        if coverage(case, covered_fact_ids(gold, chunks, candidates[:rank]))[1]:
          first_rank = rank
          break
    row.update(status=status, elapsedMs=elapsed, candidateRequirementCoverage=candidate_coverage,
        candidateAllRequired=candidate_all, contextRequirementCoverage=context_coverage,
        contextAllRequired=context_all, candidateCount=len(candidates), contextCount=len(context),
        firstAllRequiredRank=first_rank, requirementResults=requirement_results,
        allRequiredReciprocalRank=(1 / first_rank if first_rank else 0) if case["answerable"] else None)
    rows.append(row)
  answerable = [row for row in rows if row["answerable"]]
  elapsed_values = [row["elapsedMs"] for row in rows if row["elapsedMs"] is not None]
  average = lambda field: statistics.mean(row[field] for row in answerable) if answerable else None
  requirement_count = sum(len(row["requirementResults"]) for row in answerable)
  micro_coverage = lambda field: (
      sum(item[field] for row in answerable for item in row["requirementResults"]) / requirement_count
      if requirement_count else None)
  review_statuses = sorted({case["review"]["status"] for case in cases.values()})
  return {
      "kind": "fact-retrieval-evaluation", "split": split,
      "goldManifestSha256": digest(gold.manifest_path), "reviewStatuses": review_statuses,
      "humanReviewed": all(case["review"]["status"] == "human_verified" for case in cases.values()),
      "factCoveragePolicy": "exact character spans; explicitly annotated prose may bridge inter-chunk whitespace only",
      "cases": len(rows), "answerable": len(answerable),
      "missing": sum(row["status"] == "missing" for row in rows),
      "failures": sum(row["status"] == "failure" for row in rows),
      "candidateRequirementCoverage": average("candidateRequirementCoverage"),
      "requirementCoverageAggregation": "question-macro-average",
      "requiredConditions": requirement_count,
      "candidateRequirementMicroCoverage": micro_coverage("candidateSatisfied"),
      "candidateAllRequiredRecall": average("candidateAllRequired"),
      "contextRequirementCoverage": average("contextRequirementCoverage"),
      "contextRequirementMicroCoverage": micro_coverage("contextSatisfied"),
      "contextAllRequiredRecall": average("contextAllRequired"),
      "allRequiredMeanReciprocalRank": average("allRequiredReciprocalRank"),
      "elapsedMedianMs": statistics.median(elapsed_values) if elapsed_values else None,
      "elapsedP95Ms": nearest_rank(elapsed_values, .95),
      "elapsedSamples": len(elapsed_values), "percentileMethod": "nearest-rank",
      "smallSampleLatencyDiagnosticOnly": len(elapsed_values) < 100,
      "unanswerableNote": "检索非空不是拒答判定；需单独评测生成/路由。",
      "rows": rows,
  }


def main() -> None:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("command", choices=("validate", "score"))
  parser.add_argument("--manifest", type=Path, required=True)
  parser.add_argument("--minimum-review", choices=tuple(REVIEW_LEVELS), default="agent_verified")
  parser.add_argument("--variant", type=Path)
  parser.add_argument("--retrieval", type=Path)
  parser.add_argument("--split", choices=("dev", "test"), default="dev")
  parser.add_argument("--output", type=Path)
  args = parser.parse_args()
  gold = load_gold(args.manifest, args.minimum_review)
  if args.command == "validate":
    result = {"status": "valid", "documents": len(gold.documents), "facts": len(gold.facts),
        "cases": len(gold.cases), "goldManifestSha256": digest(gold.manifest_path),
        "minimumReview": args.minimum_review,
        "note": "自动契约通过不等于参考答案语义完整或人工复核。"}
  else:
    require(args.variant is not None and args.retrieval is not None, "score 需要 variant 和 retrieval")
    require(args.split != "test" or bool(gold.manifest.get("frozenAt")), "test 必须先冻结清单")
    result = score(gold, load_variant(args.variant, gold), read_jsonl(args.retrieval), args.split)
    result["variantSha256"] = digest(args.variant)
    result["retrievalSha256"] = digest(args.retrieval)
  serialized = json.dumps(result, ensure_ascii=False, indent=2)
  if args.output:
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(serialized + "\n", encoding="utf-8")
  print(serialized)


if __name__ == "__main__":
  main()
