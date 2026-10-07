#!/usr/bin/env python3
"""调用 Rehevo 评测回答接口，并以 RAGAS 对可回答 Gold 样本离线评分。"""

from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def read_jsonl(path: Path) -> list[dict[str, Any]]:
  with path.open("r", encoding="utf-8") as file:
    return [json.loads(line) for line in file if line.strip()]


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
  path.write_text(
    "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8"
  )


def post_json(url: str, payload: dict[str, Any], timeout: int) -> dict[str, Any]:
  request = urllib.request.Request(
    url,
    data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
    headers={"Content-Type": "application/json"},
    method="POST",
  )
  try:
    with urllib.request.urlopen(request, timeout=timeout) as response:
      body = json.loads(response.read().decode("utf-8"))
  except urllib.error.HTTPError as error:
    detail = error.read().decode("utf-8", errors="replace")
    raise RuntimeError(f"评测回答接口 HTTP {error.code}: {detail}") from error
  if body.get("code") != 200 or not isinstance(body.get("data"), dict):
    raise RuntimeError(f"评测回答接口失败: {body}")
  return body["data"]


def chunked(items: list[dict[str, Any]], size: int) -> list[list[dict[str, Any]]]:
  return [items[index:index + size] for index in range(0, len(items), size)]


def context_id(evidence: dict[str, Any]) -> str:
  return f"{evidence.get('documentSha256')}:{evidence.get('chunkIndex')}"


def finite_mean(values: list[Any]) -> float | None:
  valid = [float(value) for value in values if value is not None and math.isfinite(float(value))]
  return sum(valid) / len(valid) if valid else None


def git_revision() -> str | None:
  try:
    return subprocess.check_output(
      ["git", "rev-parse", "HEAD"], text=True, stderr=subprocess.DEVNULL
    ).strip()
  except (OSError, subprocess.CalledProcessError):
    return None


def main() -> int:
  parser = argparse.ArgumentParser(description=__doc__)
  parser.add_argument("--dataset", type=Path, default=Path("rehevo-gold-v1.jsonl"))
  parser.add_argument("--knowledge-base-ids", default="2,3,4,5,6,7")
  parser.add_argument("--api-base-url", default="http://localhost:8080")
  parser.add_argument(
    "--answer-model",
    default="",
    help="记录本次 Rehevo 默认回答模型 ID；用于冻结实验条件，不改变服务端配置",
  )
  parser.add_argument("--rewrite", action="store_true", help="评测 Query Rewrite + 向量检索；默认关闭以匹配当前检索基线")
  parser.add_argument(
    "--retrieval-mode",
    choices=("VECTOR", "HYBRID", "HYBRID_CONTEXT", "HYBRID_RERANK"),
    default="VECTOR",
    help="固定本次回答评测使用的检索链路",
  )
  parser.add_argument("--limit", type=int, default=0, help="最多运行多少条可回答样本，0 表示全部")
  parser.add_argument(
    "--case-ids",
    default="",
    help="逗号分隔的样本 ID；用于固定回归切片，默认运行全部可回答样本",
  )
  parser.add_argument("--batch-size", type=int, default=30)
  parser.add_argument("--request-timeout", type=int, default=1800)
  parser.add_argument("--judge-api-key-env", default="RAGAS_JUDGE_API_KEY")
  parser.add_argument("--judge-base-url", default="https://dashscope.aliyuncs.com/compatible-mode/v1")
  parser.add_argument("--judge-model", default="qwen3.7-plus")
  parser.add_argument("--judge-timeout", type=int, default=120)
  parser.add_argument("--judge-workers", type=int, default=4)
  parser.add_argument("--judge-max-tokens", type=int, default=8192)
  parser.add_argument("--output-dir", type=Path, default=Path("runs"))
  parser.add_argument(
    "--run-dir",
    type=Path,
    help="使用固定运行目录；目录存在时校验配置并从已落盘回答继续",
  )
  parser.add_argument("--answers-only", action="store_true", help="只生成回答与 RAGAS 输入，不调用评审模型")
  parser.add_argument("--dry-run", action="store_true")
  arguments = parser.parse_args()

  if not 1 <= arguments.batch_size <= 30:
    raise ValueError("batch-size must be between 1 and 30")
  if arguments.limit < 0:
    raise ValueError("limit must be non-negative")

  knowledge_base_ids = [int(value) for value in arguments.knowledge_base_ids.split(",") if value.strip()]
  cases = [case for case in read_jsonl(arguments.dataset) if case["answerable"]]
  requested_case_ids = [value.strip() for value in arguments.case_ids.split(",") if value.strip()]
  if requested_case_ids:
    case_by_id = {case["id"]: case for case in cases}
    missing_case_ids = [case_id for case_id in requested_case_ids if case_id not in case_by_id]
    if missing_case_ids:
      raise ValueError(f"数据集中不存在样本: {', '.join(missing_case_ids)}")
    cases = [case_by_id[case_id] for case_id in requested_case_ids]
  if arguments.limit:
    cases = cases[:arguments.limit]
  if not cases:
    raise ValueError("没有可回答的 Gold 样本")

  output_dir = arguments.run_dir or (
    arguments.output_dir / datetime.now(timezone.utc).strftime("ragas-%Y%m%dT%H%M%SZ")
  )
  run_metadata = {
    "dataset": str(arguments.dataset).replace("\\", "/"),
    "caseCount": len(cases),
    "caseIds": [case["id"] for case in cases],
    "knowledgeBaseIds": knowledge_base_ids,
    "rewriteEnabled": arguments.rewrite,
    "retrievalMode": arguments.retrieval_mode,
    "apiBaseUrl": arguments.api_base_url,
    "answerModel": arguments.answer_model,
    "judgeModel": arguments.judge_model,
    "judgeBaseUrl": arguments.judge_base_url,
    "gitRevision": git_revision(),
    "startedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
  }
  run_path = output_dir / "run.json"
  if run_path.exists():
    existing_metadata = json.loads(run_path.read_text(encoding="utf-8"))
    comparable_fields = (
      "dataset", "caseCount", "caseIds", "knowledgeBaseIds", "rewriteEnabled",
      "retrievalMode", "apiBaseUrl", "answerModel",
    )
    mismatches = [
      field for field in comparable_fields if existing_metadata.get(field) != run_metadata.get(field)
    ]
    if mismatches:
      raise ValueError(f"续跑配置与已有 run.json 不一致: {', '.join(mismatches)}")
    run_metadata = existing_metadata
  else:
    output_dir.mkdir(parents=True, exist_ok=False)
    run_path.write_text(json.dumps(run_metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

  if arguments.dry_run:
    print(json.dumps({"status": "DRY_RUN", **run_metadata}, ensure_ascii=False))
    return 0

  answers_path = output_dir / "answers.jsonl"
  existing_answer_rows = read_jsonl(answers_path) if answers_path.exists() else []
  answer_by_case_id = {
    row["case"]["id"]: row["answerItem"] for row in existing_answer_rows
    if row.get("case", {}).get("id") and isinstance(row.get("answerItem"), dict)
  }
  pending_cases = [case for case in cases if case["id"] not in answer_by_case_id]
  for batch in chunked(pending_cases, arguments.batch_size):
    response = post_json(
      f"{arguments.api_base_url.rstrip('/')}/api/knowledgebase/evaluation/answers",
      {
        "queries": [
          {"knowledgeBaseIds": knowledge_base_ids, "question": case["question"]}
          for case in batch
        ],
        "rewrite": arguments.rewrite,
        "retrievalMode": arguments.retrieval_mode,
      },
      arguments.request_timeout,
    )
    if len(response.get("items", [])) != len(batch):
      raise RuntimeError("评测回答接口返回的样本数量不匹配")
    for case, answer_item in zip(batch, response["items"], strict=True):
      answer_by_case_id[case["id"]] = answer_item
    write_jsonl(
      answers_path,
      [
        {"case": case, "answerItem": answer_by_case_id[case["id"]]}
        for case in cases if case["id"] in answer_by_case_id
      ],
    )

  raw_rows = []
  ragas_rows = []
  for case in cases:
    answer_item = answer_by_case_id[case["id"]]
    evidence = answer_item.get("evidence", [])
    retrieved_contexts = [item["content"] for item in evidence if item.get("content")]
    raw_rows.append({"case": case, "answerItem": answer_item})
    ragas_rows.append({
      "caseId": case["id"],
      "category": case["category"],
      "user_input": case["question"],
      "retrieved_contexts": retrieved_contexts,
      "retrieved_context_ids": [context_id(item) for item in evidence],
      "reference": case["referenceAnswer"],
      "reference_context_ids": [
        f"{item['documentSha256']}:{item['chunkIndex']}" for item in case["expectedChunkRefs"]
      ],
      "response": answer_item["answer"],
    })
  write_jsonl(answers_path, raw_rows)
  write_jsonl(output_dir / "ragas-input.jsonl", ragas_rows)

  if arguments.answers_only:
    print(json.dumps({"outputDir": str(output_dir), "answeredCases": len(ragas_rows)}, ensure_ascii=False))
    return 0

  api_key = os.getenv(arguments.judge_api_key_env)
  if not api_key:
    raise RuntimeError(f"未设置评审模型密钥环境变量: {arguments.judge_api_key_env}")
  try:
    from openai import OpenAI
    from ragas import evaluate
    from ragas.dataset_schema import EvaluationDataset, SingleTurnSample
    from ragas.llms import llm_factory
    from ragas.metrics.collections import ContextPrecision, ContextRecall, Faithfulness
    from ragas.run_config import RunConfig
  except ImportError as error:
    raise RuntimeError("缺少 RAGAS 依赖，请先安装 requirements-ragas.txt") from error

  samples = [
    SingleTurnSample(
      user_input=row["user_input"],
      retrieved_contexts=row["retrieved_contexts"],
      retrieved_context_ids=row["retrieved_context_ids"],
      reference=row["reference"],
      reference_context_ids=row["reference_context_ids"],
      response=row["response"],
    )
    for row in ragas_rows
  ]
  judge = llm_factory(
    arguments.judge_model,
    provider="openai",
    client=OpenAI(api_key=api_key, base_url=arguments.judge_base_url),
    temperature=0,
    max_tokens=arguments.judge_max_tokens,
    extra_body={"enable_thinking": False},
  )
  result = evaluate(
    EvaluationDataset(samples=samples),
    metrics=[Faithfulness(), ContextPrecision(), ContextRecall()],
    llm=judge,
    run_config=RunConfig(
      timeout=arguments.judge_timeout,
      max_retries=2,
      max_workers=arguments.judge_workers,
      seed=20260815,
    ),
    raise_exceptions=False,
    show_progress=True,
    experiment_name="rehevo-ragas-gold",
  )
  score_rows = [
    {"caseId": ragas_rows[index]["caseId"], "category": ragas_rows[index]["category"], **score}
    for index, score in enumerate(result.scores)
  ]
  summary = {
    "scores": {
      metric: finite_mean([row.get(metric) for row in score_rows])
      for metric in ("faithfulness", "context_precision", "context_recall")
    },
    "scoredCases": len(score_rows),
    "completedAt": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
  }
  (output_dir / "ragas-scores.jsonl").write_text(
    "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in score_rows), encoding="utf-8"
  )
  (output_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
  print(json.dumps({"outputDir": str(output_dir), **summary}, ensure_ascii=False))
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
