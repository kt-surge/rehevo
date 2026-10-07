#!/usr/bin/env python3
"""将 Gold v3 的 marker 计划对齐为真实 vector_store 中的 Chunk 引用。

只读访问本项目 Docker 开发库。该步骤不会调用 Embedding、LLM 或修改知识库；它只在
verify-multichunk-corpus.ps1 已冻结真实文档哈希与分块范围后执行。
"""

from __future__ import annotations

import argparse
import json
import subprocess
from collections import defaultdict
from pathlib import Path
from typing import Any


def read_json(path: Path) -> dict[str, Any]:
  return json.loads(path.read_text(encoding="utf-8"))


def query_chunks(container: str, database: str, user: str, knowledge_base_ids: list[int]) -> list[dict[str, Any]]:
  id_list = ",".join(str(item) for item in sorted(set(knowledge_base_ids)))
  sql = f"""
COPY (
  SELECT json_build_object(
    'knowledgeBaseId', kb.id,
    'documentSha256', kb.file_hash,
    'originalFilename', kb.original_filename,
    'chunkIndex', (v.metadata->>'chunk_index')::int,
    'content', v.content
  )::text
  FROM knowledge_bases kb
  JOIN vector_store v ON v.metadata->>'kb_id' = kb.id::text
  WHERE kb.id IN ({id_list})
  ORDER BY kb.id, (v.metadata->>'chunk_index')::int
) TO STDOUT;
"""
  completed = subprocess.run(
    ["docker", "exec", container, "psql", "-U", user, "-d", database, "-At", "-c", sql],
    check=False,
    capture_output=True,
    text=True,
    encoding="utf-8",
  )
  if completed.returncode != 0:
    raise RuntimeError(f"无法读取 PostgreSQL 容器 {container}: {completed.stderr.strip()}")
  try:
    return [json.loads(line) for line in completed.stdout.splitlines() if line.strip()]
  except json.JSONDecodeError as error:
    raise RuntimeError("PostgreSQL 返回的 Chunk 数据不是合法 JSON") from error


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(
    "".join(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n" for row in rows),
    encoding="utf-8", newline="\n",
  )


def source_snippet(content: str, fact: str, radius: int = 120) -> str:
  """保留证据事实前后的最小原文窗口，供人工审阅而不复制整段 Fixture。"""
  position = content.find(fact)
  if position < 0:
    return ""
  start = max(0, position - radius)
  end = min(len(content), position + len(fact) + radius)
  prefix = "…" if start > 0 else ""
  suffix = "…" if end < len(content) else ""
  return prefix + content[start:end].replace("\n", " ") + suffix


def main() -> int:
  parser = argparse.ArgumentParser(description="将 Gold v3 evidence markers 对齐到实际分块")
  parser.add_argument("--plan", type=Path, required=True, help="prepare 脚本生成的 alignment plan")
  parser.add_argument("--corpus-manifest", type=Path, required=True, help="verify 脚本生成的实际语料 manifest")
  parser.add_argument("--output", type=Path, required=True, help="输出 draft Gold JSONL")
  parser.add_argument("--alignment-output", type=Path, required=True, help="输出 marker 到证据键的对齐清单")
  parser.add_argument("--container", default="rehevo-postgres")
  parser.add_argument("--database", default="rehevo")
  parser.add_argument("--database-user", default="postgres")
  arguments = parser.parse_args()

  plan = read_json(arguments.plan)
  corpus = read_json(arguments.corpus_manifest)
  observed_documents = corpus.get("documents", [])
  if not observed_documents:
    raise ValueError("实际语料 manifest 不含 documents")

  expected_by_hash = {item["sha256"]: item for item in plan["documents"]}
  observed_by_hash = {item["documentSha256"]: item for item in observed_documents}
  if set(expected_by_hash) != set(observed_by_hash):
    missing = sorted(set(expected_by_hash) - set(observed_by_hash))
    unexpected = sorted(set(observed_by_hash) - set(expected_by_hash))
    raise ValueError(f"语料指纹不一致：missing={missing}, unexpected={unexpected}")

  chunks = query_chunks(
    arguments.container,
    arguments.database,
    arguments.database_user,
    [int(item["knowledgeBaseId"]) for item in observed_documents],
  )
  chunks_by_hash: dict[str, list[dict[str, Any]]] = defaultdict(list)
  for chunk in chunks:
    chunks_by_hash[chunk["documentSha256"]].append(chunk)

  marker_locations: dict[str, dict[str, Any]] = {}
  for document in plan["documents"]:
    digest = document["sha256"]
    document_chunks = chunks_by_hash.get(digest, [])
    if not document_chunks:
      raise ValueError(f"知识库未找到文档向量：{document['filename']} ({digest})")
    for marker in document["evidenceMarkers"]:
      matches = [chunk for chunk in document_chunks if marker in chunk["content"]]
      if len(matches) != 1:
        raise ValueError(
          f"marker 必须只落在一个实际 Chunk：{marker}, matches={len(matches)}, file={document['filename']}"
        )
      match = matches[0]
      marker_locations[marker] = {
        "documentSha256": digest,
        "chunkIndex": int(match["chunkIndex"]),
        "knowledgeBaseId": int(match["knowledgeBaseId"]),
        "originalFilename": match["originalFilename"],
      }

  rows = []
  aligned_facts = []
  for original in plan["cases"]:
    row = dict(original)
    markers = row.pop("expectedEvidenceMarkers", [])
    evidence_facts = row.pop("expectedEvidenceFacts", [])
    if row["answerable"] and not markers:
      raise ValueError(f"可回答题缺少证据 marker：{row['id']}")
    if not row["answerable"] and markers:
      raise ValueError(f"不可回答题不应带证据 marker：{row['id']}")
    if not row["answerable"] and evidence_facts:
      raise ValueError(f"不可回答题不应带证据事实：{row['id']}")
    marker_set = set(markers)
    fact_markers = [fact.get("marker") for fact in evidence_facts if isinstance(fact, dict)]
    if row["answerable"] and (len(evidence_facts) != len(markers) or set(fact_markers) != marker_set):
      raise ValueError(
        f"可回答题必须让每个 marker 对应一条证据事实：{row['id']} "
        f"markers={len(markers)}, facts={len(evidence_facts)}"
      )
    seen = set()
    references = []
    for marker in markers:
      location = marker_locations.get(marker)
      if location is None:
        raise ValueError(f"题目引用未知 marker：{row['id']} -> {marker}")
      key = (location["documentSha256"], location["chunkIndex"])
      if key not in seen:
        seen.add(key)
        references.append({"documentSha256": key[0], "chunkIndex": key[1]})
    for fact in evidence_facts:
      if not isinstance(fact, dict):
        raise ValueError(f"证据事实格式非法：{row['id']}")
      marker = fact.get("marker")
      text = fact.get("text")
      if not isinstance(text, str) or not text.strip():
        raise ValueError(f"证据事实为空：{row['id']} -> {marker}")
      location = marker_locations.get(marker)
      if location is None:
        raise ValueError(f"证据事实关联未知 marker：{row['id']} -> {marker}")
      source_chunk = next(
        (chunk for chunk in chunks_by_hash[location["documentSha256"]]
         if int(chunk["chunkIndex"]) == int(location["chunkIndex"])),
        None,
      )
      if source_chunk is None or text not in source_chunk["content"]:
        raise ValueError(f"证据事实不在 marker 所在 Chunk：{row['id']} -> {marker}")
      if text not in row["referenceAnswer"]:
        raise ValueError(f"证据事实不在参考答案：{row['id']} -> {marker}")
      aligned_facts.append({
        "caseId": row["id"],
        "marker": marker,
        "text": text,
        "documentSha256": location["documentSha256"],
        "chunkIndex": location["chunkIndex"],
        "sourceSnippet": source_snippet(source_chunk["content"], text),
      })
    row["expectedChunkRefs"] = references
    row["split"] = "pending_review"
    row["reviewStatus"] = "draft"
    rows.append(row)

  if len(rows) != 50 or sum(bool(row["answerable"]) for row in rows) != 40:
    raise ValueError("Gold v3 的题量或可回答比例不符合 50/40 契约")

  arguments.alignment_output.parent.mkdir(parents=True, exist_ok=True)
  arguments.alignment_output.write_text(json.dumps({
    "version": "gold-v3-marker-alignment-v1",
    "plan": str(arguments.plan),
    "corpusManifest": str(arguments.corpus_manifest),
    "markers": marker_locations,
    "evidenceFacts": aligned_facts,
  }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
  write_jsonl(arguments.output, rows)
  print(json.dumps({"cases": len(rows), "markers": len(marker_locations), "output": str(arguments.output)}, ensure_ascii=False))
  return 0


if __name__ == "__main__":
  raise SystemExit(main())
