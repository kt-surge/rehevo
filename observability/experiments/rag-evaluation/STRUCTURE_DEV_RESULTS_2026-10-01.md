# 结构切块：真实入库对照与决定

**决定：候选保留为可关闭的实验能力，默认仍 TOKEN；本轮没有必要证据覆盖收益，不作为最终 RAG 优化成果。**

## 实现与控制变量

新增 `DocumentChunkingService` 和 `StructureAwareDocumentSplitter`，通过 `app.ai.rag.chunking.mode` 选择 TOKEN/STRUCTURED。默认 TOKEN 保留原来的 Spring AI 切块行为。STRUCTURED 识别 Markdown 标题、段落、围栏代码和表格，优先保留完整块，超长内容按本地 Token 预算拆分。重复围栏和表头必须来自原文，记录每个片段的 UTF-16 位置，不补写摘要、事实或缺失围栏。

本轮 B 明确使用 STRUCTURED、800 Token 上限。仍是原来的四篇存储文件、相同 KB ID、相同实际解析字节、同一 DashScope Embedding 模型及 1024 维向量。原有检索配置逐项相同；查询均为 HYBRID、rewrite=false。B 新增的配置字段只是诊断信息与切块开关，详见 `retrieval-config-control.json`。

重新入库走真实产品入口：存储文件解析 → Redis Stream → 实际 Embedding → 临时向量 job 提升。原索引先完整备份在本地忽略目录，不包含 Provider/用户表或凭据。实验结束后停止实验应用，在事务中只恢复这四篇公开资料的索引；24 个 UUID、文本、元数据及向量的整体指纹与 A 完全一致。没有修改其他项目数据库。

## 结果

冻结 Gold r2 不变：34 道同 Agent 开发题，26 道可回答题、8 道资料不足题、39 条原文事实、44 项必要条件。无独立新测试集、无人工复核、无生成/评审模型调用。

| 指标 | TOKEN A | STRUCTURED B |
| --- | --- | --- |
| 实际块数 | 24 | 36 |
| 原文事实逐字保留 | 39/39 | 39/39 |
| 候选包含全部必要证据 | 26/26 | 26/26 |
| 最终上下文包含全部必要证据 | 25/26 | 25/26 |
| 最终必要条件覆盖 | 43/44 | 43/44 |
| 上下文块文字 Token 估算中位数 | 6,202.5 | 5,460.5 |
| 服务耗时 P50 / P95 | 200 / 498 ms | 206 / 438 ms |

两边都遗漏 `primary-dev-cross-02` 的必要条件。B 的上下文文字估算中位数少约 12%，但最终仍按块数取上下文；这不是相同预算的质量收益，也不是供应商计费、生成速度或用户体验收益。耗时来自不同时间各一轮顺序扫描，不能据此声称性能提升。

候选取 20，而语料仅 24/36 块，存在明显的小语料覆盖上限效应；26/26 不能推广为项目整体召回率。

### 相同预算的离线投影

复用各自保存的融合候选，按顺序装入完整块，第一块超预算即停止。没有重新请求检索/生成模型，也没有预测延迟。

| Token 估算预算 | A 完整覆盖 | B 完整覆盖 |
| --- | --- | --- |
| 2,000 | 21/26 | 20/26 |
| 3,200 | 24/26 | 24/26 |
| 4,800 | 25/26 | 25/26 |
| 6,400 | 25/26 | 25/26 |

同预算没有收益，较紧预算还产生额外遗漏。这一结果支持继续诊断，暂不支持默认启用。

## 实现限制与下一步

这批官方 HTML 先转成规范 TXT，再经过 Tika/清洗；多数标题、表格、代码围栏没有保留下来。结构切块因此主要是在纯文本上拆行。SQL/命令示例的 `#` 也可能被解释成 Markdown 标题，不能把这些元数据称为可靠的语义章节。B 还出现 `Submit` 等极短尾块，说明网页正文提取和长段末尾处理需要改进。

下一轮应先核对项目支持的文件类型及结构提取路径，在确有结构的材料上验证代码/表格完整性；同时扩大开发材料和跨文档多条件样本，诊断排序为什么漏掉第二主题的必要事实。策略冻结后再测独立的新材料。上下文预算与技术标识通道继续采用逐因素实验；生成忠实度、完整性和引用支持仍需同模型可用额度。

## 验证与复跑证据

运行目录：`runs/structure-dev-20261001/`。

- `ingestion.manifest.json`、`actual-vectorization-stream.json`、`actual-chunks.json`：同文件真实入库。
- `baseline-chunk-variant-r2.json`、`alignment-report-r2.json`：实际块与原文逐字映射；Java UTF-16 转成 Gold Unicode 码点位置。重复表头允许回到原文早期位置，清洗删除仍保持缺口。
- `structured-diagnostic/`：34 题真实请求、响应、记录、配置、源码指纹及评分。
- `comparison.json`、`paired-evidence.json`：真实结果和明确标记的离线预算投影。
- `snapshot/`：候选源码、实验工具、64 份 JUnit XML 及哈希。公开 Gold 原件已在 A 的快照保留。
- `index-backup-manifest.json`、`index-restore-verification.json`：原始索引备份/精确恢复验证，向量值只保存在忽略目录。

完整后端 fresh run：300 项，0 失败/错误/跳过。新增结构切块 9 项边界用例通过；Python 全部评测工具 30 项检查通过（25 项事实契约、5 项原文映射）。首次后端测试有 1 项失败，原因是把两次独立创建文档的随机 parent UUID 错当成应相等；修正测试后重新跑全量，保留首次失败日志。测试通过只证明已检查的行为，不是检索质量。候选快照 151 个文件的哈希复核无差异；恢复 TOKEN 启动后健康检查 HTTP200。

复跑入口：`boot-run.ps1 -ChunkingMode STRUCTURED -ChunkingMaxTokens 800`、`revectorize_primary_dev.py`、`align_primary_chunks.py`、`run_primary_dev_retrieval.py --run ...`、`token-count.init.gradle`、`summarize_structure_dev.py`。原始实验目录禁止覆盖；复跑需新目录、重新备份和重新对齐实际 Chunk ID。

S0–S4 持续目标仍 active。本轮不更新简历准确率或性能数字。
