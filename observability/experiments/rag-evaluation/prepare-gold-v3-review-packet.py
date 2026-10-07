#!/usr/bin/env python3
"""从已对齐但未复核的 Gold v3 draft 生成可人工签核的审阅包。

该工具只读取 draft 和 alignment，不会修改 JSONL、不会设置 reviewStatus，也不会生成
dev/test split。审阅人应独立核对问题、参考答案及每条证据事实后填写 decision 模板。
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path
from typing import Any


def sha256(path: Path) -> str:
  return hashlib.sha256(path.read_bytes()).hexdigest()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
  rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
  if not rows:
    raise ValueError("draft 为空")
  ids = [row.get("id") for row in rows]
  if len(ids) != len(set(ids)):
    raise ValueError("draft 含重复 case id")
  return rows


def markdown_escape(value: str) -> str:
  return value.replace("|", "\\|").replace("\n", " ")


def main() -> int:
  parser = argparse.ArgumentParser(description="生成 Gold v3 人工审阅包，不会改变 reviewStatus")
  parser.add_argument("--dataset", type=Path, required=True, help="align 脚本生成的 draft JSONL")
  parser.add_argument("--alignment", type=Path, required=True, help="align 脚本生成的 alignment JSON")
  parser.add_argument("--packet-output", type=Path, required=True, help="输出 Markdown 审阅包")
  parser.add_argument("--decision-template-output", type=Path, required=True, help="输出待填写 JSONL 决策模板")
  arguments = parser.parse_args()

  cases = read_jsonl(arguments.dataset)
  alignment = json.loads(arguments.alignment.read_text(encoding="utf-8"))
  facts_by_case: dict[str, list[dict[str, Any]]] = defaultdict(list)
  for fact in alignment.get("evidenceFacts", []):
    case_id = fact.get("caseId")
    if not case_id or not fact.get("text") or not fact.get("sourceSnippet"):
      raise ValueError("alignment 含缺少 caseId、证据事实或原文片段的记录")
    facts_by_case[case_id].append(fact)

  case_ids = {row["id"] for row in cases}
  unknown_case_ids = set(facts_by_case) - case_ids
  if unknown_case_ids:
    raise ValueError(f"alignment 引用了 draft 中不存在的 case：{sorted(unknown_case_ids)[:3]}")
  for case in cases:
    facts = facts_by_case.get(case["id"], [])
    if case.get("answerable") and not facts:
      raise ValueError(f"可回答题缺少审阅证据事实：{case['id']}")
    if not case.get("answerable") and facts:
      raise ValueError(f"不可回答题不应有审阅证据事实：{case['id']}")

  dataset_digest = sha256(arguments.dataset)
  decision_rows = []
  lines = [
    "# Gold v3 人工审阅包",
    "",
    f"- draft：`{arguments.dataset.name}`",
    f"- SHA-256：`{dataset_digest}`",
    f"- 题目数：{len(cases)}；可回答题：{sum(bool(row.get('answerable')) for row in cases)}",
    "- 本文件是审阅材料，不是冻结集；不得据此填写质量结论。",
    "",
    "## 审阅规则",
    "",
    "每题独立确认：问题没有歧义；参考答案没有超出材料；每条证据事实同时支持答案且定位正确；不可回答题确实不含所问事实。发现任一问题时，决策模板填写 `REJECT` 并在 `notes` 说明原因。所有题目保持 `draft`，直至独立复核与 dev/test 冻结均完成。",
    "",
  ]
  for ordinal, case in enumerate(cases, start=1):
    facts = facts_by_case.get(case["id"], [])
    lines.extend([
      f"## {ordinal}. {case['id']}",
      "",
      f"- 分类：`{case.get('category', '')}`；可回答：`{str(bool(case.get('answerable'))).lower()}`",
      f"- 问题：{case.get('question', '')}",
      f"- 参考答案：{case.get('referenceAnswer', '')}",
      "",
    ])
    if facts:
      lines.extend([
        "| 证据事实 | 文档 SHA / Chunk | 原文片段 |",
        "| --- | --- | --- |",
      ])
      for fact in facts:
        location = f"{fact['documentSha256'][:12]}… / {fact['chunkIndex']}"
        lines.append(
          f"| {markdown_escape(str(fact['text']))} | {location} | {markdown_escape(str(fact['sourceSnippet']))} |");
      lines.append("")
    else:
      lines.extend(["- 证据：不可回答题；请确认原材料确实不含所问事实。", ""])
    lines.append("审阅结论：`PENDING`；问题准确：`PENDING`；答案准确：`PENDING`；证据准确：`PENDING`。")
    lines.append("")
    decision_rows.append({
      "caseId": case["id"],
      "datasetSha256": dataset_digest,
      "reviewer": "",
      "reviewedAt": "",
      "verdict": "PENDING",
      "questionAccurate": None,
      "referenceAnswerAccurate": None,
      "evidenceAccurate": None,
      "notes": "",
    })

  arguments.packet_output.parent.mkdir(parents=True, exist_ok=True)
  arguments.decision_template_output.parent.mkdir(parents=True, exist_ok=True)
  arguments.packet_output.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
  arguments.decision_template_output.write_text(
    "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in decision_rows),
    encoding="utf-8", newline="\n")
  print(json.dumps({
    "datasetSha256": dataset_digest,
    "cases": len(cases),
    "answerableCases": sum(bool(row.get("answerable")) for row in cases),
    "evidenceFacts": sum(len(facts_by_case.get(row["id"], [])) for row in cases),
    "packet": str(arguments.packet_output),
    "decisionTemplate": str(arguments.decision_template_output),
  }, ensure_ascii=False))
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
