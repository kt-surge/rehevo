# Rehevo RAG 固定测评集

本目录保存 RAG 改造的可复现评测契约和金标数据，不保存大规模公开语料，也不保存用户上传的原始简历或知识库文件。

2026-10-01 新优化路线使用 [原文事实契约](FACT_GOLD_CONTRACT_2026-10-01.md) 与 [新开发集](PRIMARY_FACT_DEV_STATUS_2026-10-01.md)：跨切块策略以原文事实及必要条件对齐，候选与最终上下文分别评分。当前 34 题仅为同 Agent 自审的公开资料开发集，尚无新检索/生成成绩；以下 v1/v2/v3 记录保留各自历史边界，不与新工具分数合并。

## 两层测评集

| 层级 | 数据 | 用途 | 结论边界 |
| --- | --- | --- | --- |
| 外部回归 | T2Ranking 开发集的 200 条固定抽样 | 验证中文候选排序和 Cross-Encoder 重排是否退化 | 不能代表 Rehevo 的求职问答效果 |
| 领域金标 | Rehevo Gold v1，目标 60 条 | 验证混合召回、RRF、重排、引用和回答忠实度 | 项目量化成果只引用这一层的端到端结果 |
| 高难诊断 | Rehevo Gold v2-hard，40 条 | 验证跨文档约束、术语干扰、边界拒答与证据链 | 仅在 12 份配套语料和 Chunk 校验完成后使用 |
| 多段证据 | Rehevo Gold v3-multichunk，目标 50 条 | 验证同一原文的跨 Chunk 证据、相邻片段干扰与证据不足拒答 | 未完成真实入库、双人复核和冻结前，不产出质量结论 |
| 冒烟样本 | Orchid-417 的 3 条验收问题 | 检查上传、向量化、查询和指标链路 | 不计入任何质量指标 |

T2Ranking 的数据与代码采用 Apache-2.0；完整集体积较大，不纳入仓库。下载、筛选后只将 `qid` 清单、源版本和文件校验值写入 `external/t2ranking-200.manifest.json`。原始数据放在本机忽略目录 `data/local/`。

首次冻结或重建本地缓存：

```powershell
cd observability/experiments/rag-evaluation
python .\import-t2ranking-dev-sample.py
```

该脚本仅下载 `queries.dev.tsv` 与 `qrels.dev.tsv`，并按锁定 revision、种子和 qid 校验值生成清单；它不下载 32GB 的 `collection.tsv`。后续做候选排序或 Cross-Encoder 重排时，必须使用清单中的同一 revision 物化候选文档，不能更换集合后沿用该结果。

## Rehevo Gold v1 标注要求

正式集目标为 60 条，四类各 15 条：

1. 事实定位：材料中存在单一、可核对答案。
2. 多段综合：需要至少两个 Chunk 才能完整回答。
3. 语义改写：原问与材料的词面不完全重合，用于验证向量与关键词融合。
4. 不可回答：知识库没有依据时必须明确说明，不得编造引用。

每条必须标注 `expectedChunkRefs`，并为可回答问题提供简短 `referenceAnswer`；不可回答问题设为 `answerable=false` 且 `expectedChunkRefs=[]`。同一材料的相邻 Chunk 不得同时充当所有问题的唯一证据，避免只评到单一文档。

使用方式：复制 `rehevo-gold-v1.template.jsonl` 为未提交的本地文件，按 `schema.json` 补齐数据；完成 60 条双人复核或同一标注人二次复核后，将去敏后的金标文件提交为 `rehevo-gold-v1.jsonl`。

当前已提交的 Gold v1 是**公开资料控制集**：6 份经中文归纳的官方后端技术学习卡片、60 条人工复核题（四类各 15 条）。该语料用于验证评测链路和算法相对变化；以后接入用户真实知识库时，必须新建独立 Gold 版本，不能把两类结论混合。

第一组纯向量检索基线及其适用边界见 [baseline-results.md](baseline-results.md)。
Gold v2-hard 的独立基线与失败样本见 [hard-v2-baseline-results.md](hard-v2-baseline-results.md)。
2026-09-20 的本地 Gold v1 检索/证据门复跑与停止结论见 [gold-v1-local-20260920-results.md](gold-v1-local-20260920-results.md)。
同日 Gold v2-hard 的开发/冻结证据门结果见 [hard-v2-evidence-gate-20260920-results.md](hard-v2-evidence-gate-20260920-results.md)；冻结集拒答 Recall 未达门槛，不能开启强制拒答。

检索消融通过同一接口显式选择模式，无需重启服务：

```powershell
.\run-retrieval-baseline.ps1 -DisableRewrite -RetrievalMode VECTOR
.\run-retrieval-baseline.ps1 -DisableRewrite -RetrievalMode HYBRID
.\run-retrieval-baseline.ps1 -DisableRewrite -RetrievalMode HYBRID_CONTEXT
.\run-retrieval-baseline.ps1 -DisableRewrite -RetrievalMode HYBRID_RERANK
```

`HYBRID_CONTEXT` 是受控实验模式：从前两条候选的同一知识库补全相邻 Chunk，一次批量读取且最多保留原始 Top-K，不改变默认 `HYBRID`。它必须与 `HYBRID` 在同一固定集上比较 Recall、引用精度和 P95。`HYBRID_RERANK` 需要 `APP_AI_RAG_RERANK_ENABLED=true`、`DASHSCOPE_WORKSPACE_ID` 和百炼 API Key。原问题始终传给重排模型；Query Rewrite 只控制第一阶段候选召回，并作为独立变量记录。

## Gold v2-hard：扩充难例与干扰语料

`prepare-gold-v2-hard.py` 会在被忽略的 `data/local/backend-interview-kb-v2-hard/` 中生成 12 份中文公开资料学习卡片，并更新：

- `hard-v2-corpus.manifest.json`：每个文档的来源 URL、SHA-256 与预期 Chunk 编号；
- `rehevo-gold-v2-hard.jsonl`：40 条固定样本，四类各 10 条：跨文档、条件推理、术语干扰、边界拒答；
- `schema-v2-hard.json`：独立的 v2 数据契约。

```powershell
cd observability/experiments/rag-evaluation
python .\prepare-gold-v2-hard.py
```

运行前必须把这 12 份卡片上传到**独立**知识库，待向量化完成后核对每个文件 SHA-256 与 `chunkIndex=0`。若当前分块参数将任一文件拆成多个 Chunk，先更新清单和 Gold 引用，再建立新基线；不能沿用旧引用硬跑。v2-hard 只用于诊断相对变化，不能与 v1 的分数混合成单一“总分”。

### v2-hard 的 dev/test 契约

运行 `freeze-hard-v2-splits.py` 会按类别和 ID 交替冻结两个等量子集，并生成 `hard-v2-splits.manifest.json`：

- `rehevo-gold-v2-hard-dev.jsonl`：20 条，只用于选择融合权重、重排指令和候选数量；
- `rehevo-gold-v2-hard-test.jsonl`：20 条，只在方案冻结后运行一次；
- 两边均为四类各 5 条，其中 15 条可回答、5 条不可回答。

清单同时锁定源文件、dev 和 test 的 SHA-256。后续调整 H2 时只能查看 dev 汇总和逐题结果，不得根据 test 失败样本再次修改参数后沿用同一 test 分数。由于 H1 的 3:1 权重曾观察过完整 v2-hard，当前切分只对尚未调参的 H2 构成前瞻契约，不能追溯性地把 H1 test 分数包装成盲测结果。

## Gold v3-multichunk：建集门槛（进行中）

v1 与 v2 的本地知识库当前均是单 Chunk 文档；2026-09-20 的数据库只观察到 19 个知识库、19 个正式向量行，不能用它们的高召回证明多段证据能力。v3 必须在独立知识库按以下顺序完成：

1. 准备至少 10 份可公开复核的原始材料，每份经当前 `TokenTextSplitter` 实际入库后至少产生 3 个正式 Chunk；记录原文件 SHA-256、解析后文本 SHA-256、分块参数、Embedding 模型、知识库 ID 和每个实际 Chunk 的序号。
2. 标注至少 50 题：同文档跨段 15 题、跨文档综合 10 题、短查询/专有名词 10 题、相邻片段干扰 5 题、不可回答 10 题。可回答题必须标注全部 `expectedChunkRefs`，并为每个证据段标注一条同时出现在该段原文与参考答案中的证据事实；用 `documentSha256 + chunkIndex` 作为唯一证据键。
3. 先由标注人复核答案与全部证据，再按类别分层冻结 dev/test（建议 30/20）；调参只看 dev，test 只在方案冻结后运行一次。
4. 入库验收脚本必须拒绝以下情况：任一材料不足 3 个 Chunk、Chunk 序号不连续、金标引用不存在、不可回答题携带引用、文档指纹或分块参数与 manifest 不一致。

本服务已在向量化写入处显式固化 `chunk_index`，不再依赖底层切分器的隐式元数据；查询、离线评测与聊天快照都使用同一证据键。`section` 仍没有可靠解析来源，v3 不能将 Chunk 序号伪装为章节。

材料上传和状态变为 `COMPLETED` 后，先使用只读验收脚本冻结实际分块结果；它不调用 Embedding 或回答模型：

```powershell
.\verify-multichunk-corpus.ps1 `
  -KnowledgeBaseIds 20,21,22,23,24,25,26,27,28,29 `
  -OutputPath .\data\local\gold-v3-corpus.manifest.json
```

脚本只适用于本项目 Docker 开发环境，会核对数据库中 `knowledge_bases.chunk_count`、正式向量行和 `chunk_index` 是否一致。未通过时不能补题或运行检索基线；通过后再以输出的 SHA-256 和 Chunk 范围标注 Gold v3。

随后对齐脚本会在只读模式下从实际 `vector_store` 查找每个唯一 marker 所在的 Chunk，并强制校验每个 marker 对应的证据事实同时存在于该 Chunk 与参考答案，生成**待人工复核**的 Gold JSONL；它不会猜测 `chunkIndex`，也不会将 `draft` 自动改为 `reviewed`：

```powershell
python .\align-gold-v3-evidence.py `
  --plan .\gold-v3-multichunk-alignment-plan.json `
  --corpus-manifest .\data\local\gold-v3-corpus.manifest.json `
  --output .\data\local\rehevo-gold-v3-multichunk.draft.jsonl `
  --alignment-output .\data\local\gold-v3-marker-alignment.json
```

2026-09-20 曾完成一轮 10 份 × 4 Chunk 的真实入库和 50 条 draft 预诊断，但随后发现其中 15 条同文档跨段题错误地将不含参考事实的相邻段标为必要证据。因此该轮 Recall、MRR、nDCG 及“`HYBRID_CONTEXT` 不进入默认”的质量结论全部撤回；详情保留在 [gold-v3-multichunk-dev-diagnostic-20260920.md](gold-v3-multichunk-dev-diagnostic-20260920.md) 作为数据治理缺陷记录。该套题已被观察，不能作为最终冻结集。

修订后再次运行 marker 对齐器：30 个证据标记均定位到实际的 `documentSha256 + chunkIndex`，40 条可回答题的 65 条证据事实均同时通过“存在于 marker Chunk、存在于参考答案”校验；`run-retrieval-baseline.ps1 -ValidateOnly` 已通过结构校验，数据集 SHA-256 为 `f98c247e98d411a5394cab4e3eed2cad1f302d95ff0204e3de5af841a3998ada`。该文件仍为本地 `draft/pending_review`：对齐只证明引用存在和事实可定位，不证明人工标注正确，更不能据此产生检索质量分数或冻结测试集。

为避免人工审阅时只看汇总数而遗漏“证据事实是否真的支撑参考答案”，使用只读审阅包生成器。它读取对齐后的 draft 与 alignment，逐题列出问题、参考答案、每条证据事实及其实际 Chunk 原文片段，并生成 50 行 `PENDING` 决策模板；它不会修改 Gold 文件、`reviewStatus` 或 dev/test split：

```powershell
python .\prepare-gold-v3-review-packet.py `
  --dataset .\data\local\rehevo-gold-v3-multichunk.draft.jsonl `
  --alignment .\data\local\gold-v3-marker-alignment.json `
  --packet-output .\data\local\gold-v3-review-packet.md `
  --decision-template-output .\data\local\gold-v3-review-decisions.template.jsonl
```

2026-09-20 已在本地生成该审阅包：50 题、40 条可回答题、65 条证据事实，SHA-256 与 draft 一致。审阅人必须独立填写结论；生成成功不表示数据已复核，更不允许运行检索质量指标。

在调用检索接口前，`run-retrieval-baseline.ps1` 会始终校验题目 ID、问题、答案、可回答/不可回答的证据约束、证据键和来源指纹关系。若要将一次运行标记为“受控基线”，必须显式开启已复核与清单校验；它会额外校验 JSONL 的 SHA-256、题量、可回答数量及 `dev/test` 不混合。先用 `-ValidateOnly` 验证数据契约，不会访问服务或产生质量分数：

```powershell
.\run-retrieval-baseline.ps1 `
  -DatasetPath .\rehevo-gold-v2-hard-test.jsonl `
  -DatasetManifestPath .\hard-v2-splits.manifest.json `
  -DatasetSplit test `
  -RequireReviewed `
  -ValidateOnly
```

`gold-v3-multichunk.template.jsonl` 当前会被拒绝：其中可回答题尚未人工复核并对齐实际 Chunk。待独立标注人完成复核后，先生成带 SHA-256 的 v3 split manifest，再用同一入口验证；验证通过不等于“盲测”，测试集是否已被方案设计者看过仍须在实验记录中单独披露。

## 固定运行条件

- 运行前固定知识库版本：记录每个文档 SHA-256、Chunk 参数、Embedding 模型和向量库状态。
- 基线：当前 Query Rewrite + 向量召回；目标：关键词/向量候选 + RRF + Cross-Encoder 重排。
- 相同的 Gold v1、同一知识库版本、同一 Top-K 和回答模型下比较；模型或文档变更必须创建新 run，不覆盖旧结果。
- 检索指标：`Recall@5`、`MRR@10`、`nDCG@10`；端到端指标：回答正确率、引用正确率、不可回答拒答准确率，以及 RAGAS 的忠实度/上下文相关性。
- 每次运行写入 `runs/`（本地忽略）：配置、Git HEAD、工作区是否有未提交变更、Diff SHA-256、时间、原始检索结果、汇总指标和失败样本。不得只保留汇总百分比。

## 证据充分性观察门

`app.ai.rag.evidence-gate.mode` 默认是 `OBSERVE`：它会针对“多少、版本、COUNT、参数量”等精确数值问题检查候选 Chunk 是否包含数值证据，但不会拦截回答。运行固定 Gold 后才允许讨论 `ENFORCE`；不能把单元测试或观察结果称为拒答提升。

```powershell
.\run-evidence-gate-eval.ps1 `
  -KnowledgeBaseIds 2,3,4,5,6,7 `
  -RetrievalMode HYBRID `
  -DatasetPath .\rehevo-gold-v1.jsonl
```

脚本不调用回答模型，只走批量检索评测接口，输出每题门判断、混淆矩阵、可回答 Precision/Recall/F1 与拒答 Precision/Recall。调参只看 dev；Gold test 只在规则冻结后运行一次。`ENFORCE` 的前置门槛是：冻结集拒答 Precision、Recall 均不低于 85%，且可回答 Recall 不低于 90%。

## RAGAS 回答评测

RAGAS 是独立的离线工具，不加入 Rehevo 服务运行时。运行器调用 `/api/knowledgebase/evaluation/answers`：该接口返回**实际进入回答模型的完整检索片段**，且不会增加知识库提问计数；不要改用面向前端的 240 字 `contentPreview`。

```powershell
cd observability/experiments/rag-evaluation
python -m venv data/local/ragas-venv
.\data\local\ragas-venv\Scripts\python.exe -m pip install -r requirements-ragas.txt --timeout 180 --retries 4
$env:RAGAS_JUDGE_API_KEY = $env:ALI-API-KEY # 仅复制到当前终端，不打印、不写入文件
.\data\local\ragas-venv\Scripts\python.exe .\run-ragas-evaluation.py --dry-run --retrieval-mode VECTOR
```

首轮只运行 Gold 的可回答样本，默认关闭 Query Rewrite，以便和现有纯向量基线的检索条件一致；使用 `--rewrite` 必须新建 run，不能覆盖基线。输出会保存完整回答、实际检索片段、单题得分和汇总到被忽略的 `runs/`。

回答模型额度或网络中断时，使用固定 `--run-dir` 续跑。运行器会在每批回答后立即更新 `answers.jsonl`，并校验已有 `run.json` 中的数据集、case ID、知识库、rewrite 和检索模式，拒绝把不同配置拼入同一 run：

```powershell
python .\run-ragas-evaluation.py `
  --dataset .\rehevo-gold-v1.jsonl `
  --knowledge-base-ids 2,3,4,5,6,7 `
  --retrieval-mode HYBRID --batch-size 5 --answers-only `
  --answer-model qwen3.7-flash-2026-07-15 `
  --run-dir .\runs\ragas-faithfulness-full
```

首批指标为 `faithfulness`、`context_precision`、`context_recall`。它们分别检验回答是否由实际片段支撑、排序靠前的片段是否有用、金标答案所需事实是否被召回。不可回答题不进入这三项 RAGAS 均值，仍以“正确拒答且不伪造引用”单独统计。

RAGAS 0.4.3 与 `langchain-community` 0.4.x 存在导入兼容问题，离线 requirements 固定使用 `langchain-community==0.3.31`。全量评审存在长尾时，先让主运行器生成 `answers.jsonl` 和 `ragas-input.jsonl`，再使用可恢复评分器每批落盘：

```powershell
$env:RAGAS_JUDGE_API_KEY = $env:ALI-API-KEY
.\data\local\ragas-venv\Scripts\python.exe .\score-ragas-input.py `
  --input .\runs\<run-id>\ragas-input.jsonl `
  --batch-size 10 --max-batches 1 --judge-max-tokens 8192
```

重复相同命令会跳过三项指标均有效的 caseId，只重试缺失或 NaN 样本，并按 caseId 替换旧行。Gold v1 的第一组 H1 全量结果见 [ragas-h1-gold-v1-results.md](ragas-h1-gold-v1-results.md)，回答证据约束的低分切片结果见 [ragas-faithfulness-prompt-results.md](ragas-faithfulness-prompt-results.md)。

## 通过门槛

在未取得基线前不预设“提升百分比”。目标方案只有同时满足以下条件才可作为优化结论：

1. Gold v1 的 `Recall@5` 与基线持平或提升；
2. `MRR@10` 或 `nDCG@10` 提升，且失败样本可解释；
3. 引用正确率、拒答准确率不低于基线；
4. 记录检索与重排耗时，避免以质量提升掩盖不可接受的延迟。
