#!/usr/bin/env python3
"""生成 Gold v3 的去标识化多段面试档案与待对齐的 50 条题目。

这不是用户简历或线上日志。所有档案均为受控 Fixture，用于验证一份材料被实际切成
多个 Chunk 后，检索、证据引用和拒答链路是否仍可复现。最终 Gold 只能由
align-gold-v3-evidence.py 在真实向量库中定位证据 Chunk 后生成。
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parent
CORPUS_DIR = ROOT / "data/local/rehevo-gold-v3-multichunk"
MANIFEST_PATH = ROOT / "gold-v3-multichunk-corpus.template.manifest.json"
PLAN_PATH = ROOT / "gold-v3-multichunk-alignment-plan.json"
TEMPLATE_PATH = ROOT / "rehevo-gold-v3-multichunk.template.jsonl"

# 每份档案有三个目标段落。正文填充只用于稳定跨过默认切分阈值；唯一事实与 marker
# 只出现一次，最终仍以真实入库后的 content/metadata 对齐，而非预测 Chunk 序号。
PACKS = [
  {"id": "P01", "role": "Java 支付后端", "skill": "事务边界", "claim": "把扣款记录与 Outbox 事件拆分为独立事务后，再由提交后投递保证顺序", "signal": "能先说明代理边界，再比较 REQUIRED 与 REQUIRES_NEW", "risk": "未说明连接池在并发独立事务下的容量", "action": "安排一次 20 分钟事务与可靠投递追问", "code": "JTX-41"},
  {"id": "P02", "role": "Go 平台后端", "skill": "幂等消费", "claim": "用业务订单号和状态机吸收 Redis Stream 的重复投递", "signal": "能解释先处理后 XACK 仍不是端到端恰好一次", "risk": "没有给出 Pending 恢复的最小空闲时间依据", "action": "安排一次故障恢复与幂等设计追问", "code": "GST-52"},
  {"id": "P03", "role": "AI 应用后端", "skill": "RAG 证据", "claim": "保存回答实际使用的检索片段与文档哈希，而非只保存生成答案", "signal": "能区分候选召回不足与重排失效", "risk": "引用覆盖率尚未给出冻结集结果", "action": "安排一次证据链与拒答策略追问", "code": "RAG-63"},
  {"id": "P04", "role": "实时通信后端", "skill": "语音 Turn", "claim": "为语音回复事件分配 turnId、eventId 与递增 sequence，并在取消后拦截迟到回调", "signal": "能解释音频乱序为什么需要按句子序号回放", "risk": "尚未说明浏览器 cancel ack 的 P95 门槛", "action": "安排一次取消与乱序故障追问", "code": "VCE-74"},
  {"id": "P05", "role": "数据平台后端", "skill": "可观测性", "claim": "将批处理失败、重试和端到端耗时分别记录，避免只报平均耗时", "signal": "能将 P95 长尾与具体失败样本关联", "risk": "没有说明阈值变更如何避免告警风暴", "action": "安排一次指标口径与告警设计追问", "code": "OBS-85"},
  {"id": "P06", "role": "Java 基础架构", "skill": "异步可靠性", "claim": "用 Redis Stream 的 Pending 恢复和业务幂等保护异步任务重投", "signal": "能区分确认、领取和业务去重三个责任边界", "risk": "没有给出死信后的人工处置路径", "action": "安排一次积压恢复与补偿策略追问", "code": "ASY-96"},
  {"id": "P07", "role": "检索平台后端", "skill": "混合检索", "claim": "以关键词和向量候选的名次做 RRF 融合，再对有限候选重排", "signal": "能说明不同分数量纲不能直接相加", "risk": "未量化 rerank 带来的 P95 代价", "action": "安排一次检索消融与延迟权衡追问", "code": "HYB-17"},
  {"id": "P08", "role": "面试产品后端", "skill": "评分可信度", "claim": "把已回答、成功评分和评分失败分开计入覆盖率，不把失败项默认为零分", "signal": "能说明分批评分异常不应拖垮整场报告", "risk": "人工复核样本还未冻结", "action": "安排一次评分降级与人工复核追问", "code": "EVA-28"},
  {"id": "P09", "role": "云原生后端", "skill": "容量治理", "claim": "在固定负载下同时比较检索 Recall、P95 与候选数量，而不是只看单题效果", "signal": "能将资源预算与质量门槛一起写入发布条件", "risk": "没有给出突发流量下的降级顺序", "action": "安排一次容量压测与降级策略追问", "code": "CAP-39"},
  {"id": "P10", "role": "安全工程后端", "skill": "数据边界", "claim": "将评测语料与真实用户资料隔离，并为每次运行记录数据指纹", "signal": "能说明为什么冻结测试集不能反复用于调参", "risk": "未给出敏感字段脱敏后的抽检方法", "action": "安排一次数据留存与评测治理追问", "code": "GOV-40"},
]


def sha256(path: Path) -> str:
  return hashlib.sha256(path.read_bytes()).hexdigest()


def padding(packet: dict[str, str], section: str) -> str:
  sentences = []
  for index in range(1, 11):
    sentences.append(
      f"补充记录 {index}：这一段只用于固定受控语料的分块边界，"
      "任何结论都必须回到可定位的原文证据；它不包含个人身份信息、线上数值或未记录的业务事实。"
    )
  return "\n".join(sentences)


def render_packet(packet: dict[str, str]) -> tuple[str, list[str]]:
  resume_marker = f"[EVIDENCE:{packet['id']}:resume]"
  answer_marker = f"[EVIDENCE:{packet['id']}:answer]"
  plan_marker = f"[EVIDENCE:{packet['id']}:plan]"
  text = f"""# Rehevo Gold v3 受控多段面试档案 {packet['id']}

> 说明：这是不含个人身份信息的评测 Fixture，不是用户简历、录音、在线面试记录或生产配置。

## 01 经历与目标岗位

{resume_marker}

档案编号为 {packet['code']}，目标岗位是 {packet['role']}，本轮验证的核心能力是“{packet['skill']}”。候选人陈述的项目做法是：{packet['claim']}。这条陈述只用于检索和引用准确性验证，不代表产品当前线上方案。

{padding(packet, '经历与目标岗位')}

## 02 回答证据与能力判断

{answer_marker}

在模拟追问中，候选人的有效表现是：{packet['signal']}。评估时需要把候选人已经说出的证据与尚未证实的内容区分开，不能因为表达流畅就补全缺失事实。当前需要继续验证的风险是：{packet['risk']}。

{padding(packet, '回答证据与能力判断')}

## 03 下一轮计划与通过条件

{plan_marker}

建议的下一步是：{packet['action']}。通过条件是候选人能给出一条可追溯的技术证据、一个失败或边界场景，以及与该场景相符的验证指标；如果材料没有给出某个具体阈值，就应明确说明无法确认，而不是编造数字。

{padding(packet, '下一轮计划与通过条件')}
"""
  return text, [resume_marker, answer_marker, plan_marker]


def add_case(rows: list[dict], category: str, question: str, answer: str,
             answerable: bool, sources: list[str], markers: list[str],
             evidence_facts: list[dict[str, str]] | None = None) -> None:
  rows.append({
    "id": f"rehevo-gold-v3-multichunk-{len(rows) + 1:03d}",
    "split": "pending_alignment",
    "category": category,
    "question": question,
    "answerable": answerable,
    "expectedChunkRefs": [],
    "expectedEvidenceMarkers": markers,
    "expectedEvidenceFacts": evidence_facts or [],
    "referenceAnswer": answer,
    "sourceFilenames": sources,
    "reviewStatus": "draft",
  })


def build_cases() -> list[dict]:
  rows: list[dict] = []
  for packet in PACKS[:5]:
    packet_id = packet["id"]
    filename = f"{packet_id.lower()}-controlled-interview-packet.md"
    add_case(rows, "intra_document_multichunk",
      f"{packet_id} 的目标岗位与项目做法是什么，已表现出的能力和仍需验证的风险又分别是什么？",
      f"目标岗位是{packet['role']}，做法是{packet['claim']}；已表现出{packet['signal']}，"
      f"仍需验证{packet['risk']}。",
      True, [filename], [f"[EVIDENCE:{packet_id}:resume]", f"[EVIDENCE:{packet_id}:answer]"], [
        {"marker": f"[EVIDENCE:{packet_id}:resume]", "text": packet["claim"]},
        {"marker": f"[EVIDENCE:{packet_id}:answer]", "text": packet["signal"]},
      ])
    add_case(rows, "intra_document_multichunk",
      f"{packet_id} 已表现出的能力、仍需验证的风险与下一轮行动分别是什么？",
      f"已表现出{packet['signal']}；仍需验证{packet['risk']}；下一轮应{packet['action']}。",
      True, [filename], [f"[EVIDENCE:{packet_id}:answer]", f"[EVIDENCE:{packet_id}:plan]"], [
        {"marker": f"[EVIDENCE:{packet_id}:answer]", "text": packet["risk"]},
        {"marker": f"[EVIDENCE:{packet_id}:plan]", "text": packet["action"]},
      ])
    add_case(rows, "intra_document_multichunk",
      f"针对 {packet_id} 的核心能力，下一轮应做什么追问，且回答需满足哪些通过条件？",
      f"核心能力是“{packet['skill']}”；应{packet['action']}；回答需要给出可追溯技术证据、"
      "失败或边界场景及对应验证指标。",
      True, [filename], [f"[EVIDENCE:{packet_id}:resume]", f"[EVIDENCE:{packet_id}:plan]"], [
        {"marker": f"[EVIDENCE:{packet_id}:resume]", "text": f"核心能力是“{packet['skill']}”"},
        {"marker": f"[EVIDENCE:{packet_id}:plan]", "text": packet["action"]},
      ])

  for left, right in zip(PACKS[:5], PACKS[5:]):
    left_filename = f"{left['id'].lower()}-controlled-interview-packet.md"
    right_filename = f"{right['id'].lower()}-controlled-interview-packet.md"
    add_case(rows, "cross_document",
      f"{left['id']} 和 {right['id']} 分别需要重点验证什么能力？",
      f"{left['id']} 重点验证{left['skill']}；{right['id']} 重点验证{right['skill']}。",
      True, [left_filename, right_filename], [f"[EVIDENCE:{left['id']}:resume]", f"[EVIDENCE:{right['id']}:resume]"], [
        {"marker": f"[EVIDENCE:{left['id']}:resume]", "text": left["skill"]},
        {"marker": f"[EVIDENCE:{right['id']}:resume]", "text": right["skill"]},
      ])
    add_case(rows, "cross_document",
      f"比较 {left['id']} 与 {right['id']} 的下一轮行动，各自应安排什么？",
      f"{left['id']}：{left['action']}；{right['id']}：{right['action']}。",
      True, [left_filename, right_filename], [f"[EVIDENCE:{left['id']}:plan]", f"[EVIDENCE:{right['id']}:plan]"], [
        {"marker": f"[EVIDENCE:{left['id']}:plan]", "text": left["action"]},
        {"marker": f"[EVIDENCE:{right['id']}:plan]", "text": right["action"]},
      ])

  for packet in PACKS:
    packet_id = packet["id"]
    filename = f"{packet_id.lower()}-controlled-interview-packet.md"
    add_case(rows, "short_entity",
      f"档案 {packet_id} 的受控编号是什么？",
      f"{packet['code']}。", True, [filename], [f"[EVIDENCE:{packet_id}:resume]"], [
        {"marker": f"[EVIDENCE:{packet_id}:resume]", "text": packet["code"]},
      ])

  for left, right in zip(PACKS[:5], PACKS[5:]):
    left_filename = f"{left['id'].lower()}-controlled-interview-packet.md"
    right_filename = f"{right['id'].lower()}-controlled-interview-packet.md"
    add_case(rows, "adjacent_distractor",
      f"在 {left['id']} 和 {right['id']} 中，哪个档案的风险是“{left['risk']}”？",
      f"{left['id']}。{left['risk']}", True, [left_filename, right_filename], [f"[EVIDENCE:{left['id']}:answer]"], [
        {"marker": f"[EVIDENCE:{left['id']}:answer]", "text": left["risk"]},
      ])

  for packet in PACKS:
    packet_id = packet["id"]
    filename = f"{packet_id.lower()}-controlled-interview-packet.md"
    add_case(rows, "boundary_refusal",
      f"{packet_id} 的真实姓名、联系方式和上一家公司名称分别是什么？",
      "这是去标识化评测 Fixture，材料不含真实姓名、联系方式或公司名称，无法确认。",
      False, [filename], [])

  if len(rows) != 50:
    raise AssertionError(f"Gold v3 应生成 50 题，实际为 {len(rows)}")
  return rows


def main() -> None:
  CORPUS_DIR.mkdir(parents=True, exist_ok=True)
  documents = []
  for packet in PACKS:
    filename = f"{packet['id'].lower()}-controlled-interview-packet.md"
    content, markers = render_packet(packet)
    path = CORPUS_DIR / filename
    path.write_text(content, encoding="utf-8", newline="\n")
    documents.append({
      "filename": filename,
      "sha256": sha256(path),
      "sourceType": "controlled_deidentified_fixture",
      "source": "Rehevo Gold v3 controlled fixture; not user data",
      "minimumExpectedChunks": 3,
      "evidenceMarkers": markers,
    })

  filename_to_hash = {item["filename"]: item["sha256"] for item in documents}
  cases = build_cases()
  for case in cases:
    case["sourceDocumentHashes"] = [filename_to_hash[name] for name in case.pop("sourceFilenames")]

  MANIFEST_PATH.write_text(
    json.dumps({"version": "gold-v3-multichunk-template-v1", "documents": documents}, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8", newline="\n"
  )
  PLAN_PATH.write_text(
    json.dumps({"version": "gold-v3-alignment-plan-v1", "documents": documents, "cases": cases}, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8", newline="\n"
  )
  TEMPLATE_PATH.write_text(
    "".join(json.dumps(case, ensure_ascii=False, separators=(",", ":")) + "\n" for case in cases),
    encoding="utf-8", newline="\n"
  )
  print(json.dumps({"documents": len(documents), "cases": len(cases), "corpus": str(CORPUS_DIR)}, ensure_ascii=False))


if __name__ == "__main__":
  main()
