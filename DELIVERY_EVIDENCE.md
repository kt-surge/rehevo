# Rehevo 技术升级验收总表

更新时间：2026-09-20。此表区分“代码存在”“受控验证通过”和“可用于项目量化表述”；后者必须同时满足数据集、运行条件和验收门槛。

| 方向 | 当前已验证证据 | 量化状态 | 能否写成效果成果 | 下一道门槛 |
| --- | --- | --- | --- | --- |
| 评分可信度 | `SCORED / UNANSWERED / EVALUATION_FAILED` 分离；证据回查、限时、单题重试与有界批处理均有回归测试；CLAWKIT 文本 Provider 已完成真实连通、故障复现与一次修复后复验 | `rehevo-evaluation-seed-v1` 为 30 条 `draft`；修复前 3 次预运行 90 次中完成 64 次（覆盖率 71.1%），修复后独立 1 次为 30/30（100%） | 否 | 人工复核 Gold；在修复版本上完整重复运行 3 次，达到覆盖率至少 99%、分数极差中位数不高于 8、证据覆盖率至少 90% |
| 多段 RAG | 10 份受控材料实际各 4 Chunk；50 条 draft（40 可回答）已重新对齐，65 条证据事实均可定位到实际 Chunk 与参考答案 | v3 draft SHA-256：`f98c247e98d411a5394cab4e3eed2cad1f302d95ff0204e3de5af841a3998ada` | 否 | 人工复核、独立 dev/test 冻结；仅在未暴露 test 上验收 Recall@5、引用正确率/覆盖率和拒答 F1 |
| RAG 历史 v3 预诊断 | 已发现 15 条同文档题误标必要证据段，并增加自动证据事实校验防回归 | 历史 Recall/MRR/nDCG 和 `HYBRID_CONTEXT` 对照均已撤回 | 否 | 不复用该已暴露数据产生新质量结论 |
| 实时语音 | Turn 状态机、取消确认、迟到/乱序丢弃、Fake ASR/LLM/TTS 故障矩阵、40 路出站顺序均有受控测试 | Fake 取消 20 次受 `<200ms` P95 门槛；浏览器开场取消真实链路仅 `n=1`（服务端 2.184ms、客户端 8ms） | 否 | 同配置完成至少 20 次真实正常 Turn，并与改造前按 P50/P95、成功率和错误率对照 |
| 核心面试闭环 | 训练任务按能力点合并；5 为最高优先级；模型重复任务会被服务端归一化并写入低基数指标 | 定向用例验证 3 条模型任务收敛为 2 条，重复项计数为 1；`retest-sequence-v1` 固定 4 组 | 否 | 以人工复核的跨场样本验证弱项复测覆盖率、重复题率和评分改善 |

## 本轮可复跑校验

```powershell
.\gradlew.bat :app:cleanTest :app:test --no-daemon

python -m py_compile `
  .\observability\experiments\rag-evaluation\prepare-gold-v3-multichunk.py `
  .\observability\experiments\rag-evaluation\align-gold-v3-evidence.py

.\observability\experiments\rag-evaluation\run-retrieval-baseline.ps1 `
  -DatasetPath .\data\local\rehevo-gold-v3-multichunk.draft.jsonl `
  -KnowledgeBaseIds 30,31,32,33,34,35,36,37,38,39 `
  -ValidateOnly
```

2026-09-20 本轮执行结果：后端 267 项测试通过；v3 结构校验通过，但 `reviewed=false`，因此运行器不会把它当作受控质量基线。

## 本轮预运行故障证据

- 文本默认 Provider 已切至本地加密配置的 `clawkit`（模型 `deepseek-v4-flash`）；连接测试成功，向量 Provider 保持原 DashScope 配置。
- 临时开启本地 benchmark 后，基于 `rehevo-evaluation-seed-v1` 的 30 条 `draft` 样本完成 3 次预运行，原始结果为 `observability/experiments/interview-evaluation/runs/evaluation-stability-20260920-230709.json`。该文件记录数据集 SHA-256、运行时长与逐题状态，不是人工复核的质量基线。
- 汇总为：90 次应评分、64 次已评分、64 次有回答证据，评分覆盖率 `71.11%`，已评分样本的分数极差中位数 `2.5`、等级极差中位数 `0`、排序准确率 `80%`、预期分段命中率 `56.67%`。26 次 `EVALUATION_FAILED` 使报告在 180 秒预算到期时降级，故不得把稳定样本的低波动表述为“评分稳定”。
- 后端日志已定位失败模式：`deepseek-v4-flash` 多次省略 `keyPoints`、`questionIndex`、`rubricLevel` 或 `score`，另有一次字符串内未转义换行；统一 Schema 校验触发重试，最终耗尽整份报告预算并跳过二次汇总。统一重试提示与评分 Prompt 已补充“所有 Schema 字段必须显式返回；空数组为 `[]`、空字符串为 `\"\"`；逐题顺序与数量必须一致”的约束。
- 修复后，同一 Provider、同一 30 条 `draft` 的独立单次复验见 `observability/experiments/interview-evaluation/runs/evaluation-stability-20260920-233616.json`：30/30 已评分且均含可回查回答证据，覆盖率与证据覆盖率均为 `100%`，用时 `151.891s`，排序准确率 `100%`，预期分段命中率 `60%`。这只说明字段完整性回归通过一次；样本未人工复核且缺少重复次数，不构成评分质量、稳定性或简历量化结论。
- benchmark 开关已通过重启恢复默认关闭；调用端收到统一错误响应 `code=404`，没有把实验接口暴露在常规本地启动中。
