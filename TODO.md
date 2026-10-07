# InterviewGuide TODO

> 2026-10-01 当前优化入口：[第一轮需求、方案评审与持续目标](REHEVO_OPTIMIZATION_GOAL_AND_DESIGN_2026-10-01.md)。本轮按 S0–S4 推进；下文保留此前里程碑与历史记录，完成状态须结合当前实验重新核验。

> 当前主线：先建立可信基线与可观测闭环，再依次推进评估可信度、RAG/PREP、实时 Turn 和性能优化。
>
> 策略来源：`C:\Users\yngtao\Documents\Codex\2026-07-28\wo\outputs\AI面试开源项目横向调研与InterviewGuide融合策略.md`
>
> 最近核对：2026-09-19。`[x]` 只表示当前源码或本次命令已验证；历史运行记录和设计文档不算当前完成证据。当前执行与量化门槛见 `TECHNICAL_EXECUTION_PLAN.md`。

## 当前结论

- 当前不是继续增加页面或引入新框架，而是把已有语音、评估、RAG 能力做成可验证闭环。
- 当前里程碑为 **M0：可信基线 + 可观测第一版**。
- M0 完成前，不并行改 Rubric 数据模型、RAG 检索算法、Turn 协议和 TTS 连接池。
- `docs/TECHNICAL_HIGHLIGHTS_AND_SAAS_EVOLUTION.md` 是另一条 SaaS 规划，不作为本轮实施顺序。

## 当前事实

- [x] 默认单测通过：200 个测试，0 失败，0 跳过；集成测试通过：21 个测试，0 失败，0 跳过。
- [x] 前端生产构建通过。
- [x] Actuator 已暴露 `health`、`info`、`metrics`、`prometheus`。
- [x] 语音 Handler 已记录部分 ASR、LLM、TTS、Turn 指标。
- [x] 测试基线可信：默认单测与需要 Redis/Spring 上下文的集成测试已分组，两组均无跳过项。
- [x] RAG 运行可观测：向量化、主/兜底检索、改写和回答已写入专属 Micrometer 指标；本轮真实查询已在 Prometheus 验证。
- [x] 监控配置已版本化：`observability/` 包含 Prometheus 抓取、Grafana 自动装载、三张 Dashboard、七条告警与 RAG 基线实验记录；本轮仍需启动容器验证闭环。
- [ ] RAG 来源可追溯：`QueryResponse` 当前只有答案、知识库 ID 和名称，没有来源片段与相似度。
- [x] 实时 Turn 已具备最小协议：`VoiceTurnCoordinator` 为 LLM/TTS 出站事件分配 `turnId/eventId/sequence`，取消或新 Turn 后会拦截旧回调；仍缺 Fake Provider 的完整故障矩阵和真实基线对照。

## M0：可信基线 + 可观测第一版（现在做）

### 0.1 修复并冻结可信测试基线

- [x] 重写 `VoiceInterviewServiceTest` 的依赖装配，注入 `LlmProviderRegistry`、`VoiceEvaluateStreamProducer` 和评估 Repository，移除整类 `@Disabled`。
- [x] 逐项审计其余跳过测试，恢复占位测试，并把需要 Redis/Spring 上下文的场景纳入集成测试组。
- [x] 为外部依赖测试增加 `integration` Tag 和独立 `integrationTest` 命令，默认单测与集成测试边界清楚。
- [x] 使用 `--rerun-tasks` 重新执行后端测试，不依赖 `UP-TO-DATE` 结果。
- [x] 启动 PostgreSQL、Redis、RustFS 和后端，`/actuator/health` 返回 `UP`，`/actuator/prometheus` 可抓取。
- [x] 通过真实页面完成三条主链路：
  - [x] 文本面试提交回答并结束，会话最终为 `EVALUATED / COMPLETED`。
  - [x] 文档上传后状态为“已完成”，问答返回样本中的唯一答案 `Orchid-417`。
  - [x] 语音页面本轮已验证真实会话创建、开场 TTS、ASR ready、暂停/恢复/结束；用户确认此前人工“说话 → ASR 转写 → 提交”验收无异常。

本轮命令：`gradlew :app:test --no-daemon --rerun-tasks`；`gradlew :app:integrationTest --no-daemon --rerun-tasks`。

**0.1 状态：完成。** 默认单测无跳过，默认单测与集成测试边界明确，三条主链路均有自动化或人工验收依据。

完成门槛：默认单测无非预期跳过；三条主链路都有本轮终端或页面证据；失败路径不会被“构建成功”替代。

### 0.2 建立指标契约

- [x] 盘点 Spring AI 2.0 已提供的模型、Token、工具和向量库 Observation，复用框架 Observation；业务指标仅补框架未覆盖的语音、Stream 和 RAG 口径（见 `METRICS_CONTRACT.md`）。
- [x] 新建 `common/metrics/`，集中管理指标名称、低基数标签和记录入口。
- [x] 统一现有语音指标命名，并补齐：活跃会话、ASR ready、首音频、重连、丢音频和取消。
- [x] 在 `AbstractStreamConsumer` 补齐任务吞吐、处理耗时、重试、恢复、Pending idle 年龄和 Redis consumer group backlog。
- [x] 为 RAG 增加运行指标，但不改检索算法：
  - [x] 向量化成功/失败、耗时和 Chunk 数；
  - [x] 主检索/兜底检索耗时、命中数和无结果；
  - [x] Query rewrite 成功/失败/跳过；
  - [x] 回答耗时和失败。
- [x] 为指标增加测试，验证名称、单位、状态口径和标签白名单。

标签红线：`sessionId`、`turnId`、`userId`、`knowledgeBaseId`、问题、Prompt、文档名和原文只能进入日志或 Trace，不能进入 Prometheus 标签。

本轮验证：`METRICS_CONTRACT.md` 固化指标契约；默认测试 200 个、集成测试 21 个均通过；真实知识库查询返回 `Orchid-417`，Prometheus 已出现 `app_rag_retrieval_seconds` 与 `app_rag_answer_seconds`。Dashboard 面板和告警规则归入 0.3。

完成门槛：每项指标都有名称、类型、单位、成功/失败口径、允许标签、Dashboard 面板和对应告警；不出现无界标签。

### 0.3 落地监控配置

- [x] 新建 `observability/`：
  - [x] `prometheus/`：抓取配置和规则加载；
  - [x] `grafana/provisioning/`：数据源和 Dashboard 自动装载；
  - [x] `grafana/dashboards/`：三张可导入看板；
  - [x] `alerts/`：七条带恢复动作的告警；
  - [x] `experiments/`：RAG 基线实验步骤与结果记录。
- [x] 在开发 Compose 中接入 Prometheus 和 Grafana，默认 Prometheus 端口为 9091，避免占用已有 9090。
- [x] 建立三张 Dashboard：实时语音体验、异步任务可靠性、AI/RAG 质量与成本代理指标。
- [x] 告警覆盖 Turn 错误率、首音频 P95、ASR 重连、Stream backlog、最老 Pending、结构化输出失败和 RAG 错误。
- [x] 使用固定知识库完成真实 RAG 基线：服务端 3 次成功、平均回答 120.548 秒（第 3 次约 317 秒长尾）、平均主检索 0.448 秒、每次命中 1 个 Chunk；客户端只收到 2 次响应，第 3 次 90 秒超时后服务端仍继续执行，已如实记录（见 `observability/experiments/rag-baseline.md`）。
- [x] 已启动 Prometheus/Grafana：`up{job="rehevo-app"}=1`，七条告警规则均为 `health=ok`；Grafana 健康检查通过，数据库已出现 `Rehevo` provision 文件夹、数据源和三份 Dashboard 定义。

完成门槛：重启环境后自动加载数据源、看板和规则；Prometheus target 为 UP；每条告警都有触发条件、持续窗口和恢复动作。

**0.3 状态：完成。**

### 0.4 记录基线并完成故障闭环

- [x] 固化 20 Turn 的真实语音采集协议、固定组成、原始指标取数和异常分类（见 `observability/experiments/voice-baseline.md`）。
- [ ] 使用同一配置完成至少 20 次正常语音 Turn，记录 ASR ready、首 Token、首音频、TTS、整轮耗时和成功率。
- [ ] 记录评估任务耗时、向量化耗时、RAG 检索耗时与无结果比例。
- [ ] 人为增加 TTS 延迟，验证首音频 P95/P99 和告警同步变化。
- [ ] 恢复 TTS，验证告警恢复且指标回落。
- [ ] 再完成 ASR 断连、异步任务失败两项实验，验证重连、重试、最终失败和积压可被定位。
- [ ] 保存命令、配置、时间窗口、截图、原始数据和结论；不只保存成功截图。

M0 最终验收：打开 Grafana 能定位一次完整语音面试的 ASR → LLM → TTS → 异步评估耗时；TTS 故障能触发告警并在恢复后回落；RAG 向量化失败和无结果比例可见。

## 后续里程碑（M0 验收后按顺序进入）

### M1：评估可信度

- [x] 文字面试题目随会话 JSON 持久化 `competency`、0—4 级 Rubric、关键点、追问方向和来源；历史题目缺失字段时保持兼容。
- [x] 每题评价保存命中 Rubric 等级、可定位的回答原文证据、缺失点、事实风险和下一步动作。
- [ ] 单题失败隔离并使用有限并发；失败项不拖垮整份报告。
  - [x] 批次缺失或失败后，对有回答的失败题执行最多 3 次报告级单题重试，并记录 recovered/failure 指标。
  - [x] 批次执行改为独立有界执行器；默认仍串行（并发上限 1），替身试验证明配置上限 2 时不会超过 2，单批失败不阻断其余题或汇总。真实 Provider 的限流与失败率前后对照尚未完成，禁止据此提高默认并发。
- [x] 分离评分 Coverage 与总分；未回答题和评估失败题不参与能力平均，并在题目级明确标记状态。
- [x] 增加报告级已回答数、成功评分数、失败数、覆盖率及对应 Prometheus 指标。
- [x] 增加证据覆盖率；模型返回但无法在候选人回答中逐字定位的“证据”会被服务端丢弃。
- [x] 报告生成并持久化 3—5 个绑定题目、原因、行动、完成标准和优先级的训练任务；无有效评分时不伪造任务。
- [x] 建立 10 组 × 3 档回答的 30 条评分 seed、重复运行入口和汇总脚本；接口默认关闭。
- [~] 人工复核并冻结评分 Gold 集，真实重复运行 3 次后验证覆盖率、分数极差、等级排序与区间命中率。已确认 Provider 不可用时单批约 61 秒失败；模型读取默认上限已设为 60 秒，整份报告另有 180 秒调用预算，预算耗尽后停止后续调用并将剩余已回答题标为评估失败。当前尚无有效质量基线。

进入 M2 的条件：报告中的每个结论都能定位到回答证据，并明确展示覆盖率和失败项。

### M2：可信 RAG + PREP

- [x] `QueryResponse` 返回候选来源、Chunk、摘要与检索/融合/重排分数。
- [~] 检索证据与聊天快照已返回文档哈希、片段序号、检索来源、原始文件名和文件类型；向量化写入已显式固化连续 `chunk_index` 并通过多 Chunk 单测。公开 v1/v2 基线仍是单 Chunk；独立 Gold v3 已实际入库 10 份 × 4 Chunk，章节仍待解析器提供可靠来源，不能由片段序号代替。
  - [x] 增加 `verify-multichunk-corpus.ps1`：只读校验向量化状态、Chunk 总数与连续索引，并冻结实际文档 SHA-256/Chunk 范围；已对 Gold v3 的 10 份真实受控材料完成结构验收。
  - [x] 增加 Gold v3 受控 Fixture 生成与 marker 对齐工具：生成 10 份去标识化多段档案、50 条（40 可回答/10 拒答）待复核题；实际入库后才以 `documentSha256 + chunkIndex` 生成 draft，且每个可回答 marker 必须有一条同时存在于实际 Chunk 与参考答案的证据事实；脚本不会自动标记人工复核通过。
  - [x] 检索基线入口已加入 Gold 数据契约校验：可选强制 `reviewed`、JSONL SHA-256、题量、可回答数与 dev/test split manifest 一致；`-ValidateOnly` 可在不调用服务的情况下拒绝未对齐或未复核数据。修订后的 v3 draft（50/40，SHA-256 `f98c…8ada`）已通过结构校验，仍是未复核数据，不是检索质量结果。
  - [~] 已真实导入 10 份 × 4 Chunk 受控档案。此前 50 条 draft 的预诊断发现 15 条同文档题的必需证据标注错误，所有 Recall/MRR/nDCG 与 `HYBRID_CONTEXT` 对照结论均已撤回；修订后不得在该已暴露数据集上继续调参或形成质量结论，需独立新集做冻结验收。
- [x] RAG 聊天在回答完成时持久化本轮实际检索证据快照，前端历史回放可展开查看来源卡片；历史旧消息保持空证据兼容。
- [~] 增加证据充分性观察门：已覆盖精确数值、配置/模型、生产事实和绝对断言；2026-09-20 在 v2-hard 开发集达到 20/20，但冻结测试集拒答 Recall=40%，保持 `OBSERVE`，不得切换 `ENFORCE`。测试集不得再用于调参；下一步新建 Gold 版本后比较属性—断言抽取与受控 LLM verifier。
- [x] 增加结构化路由观察器：同步查询、流式问答与离线检索评测返回/记录 `RETRIEVE`、`CLARIFY`、`ABSTAIN` 建议及固定原因；默认 `OBSERVE`，不改变当前检索与回答。`routing-observer-v1` 固定 4 场景验证无历史指代、带历史指代、无证据和证据不足的建议一致性；这不是用户质量集，也不能启用自动澄清或拒答。
- [~] 已有 60 条公开资料控制集，验证前排命中与拒答；仍需为真实用户资料建立独立固定集，并完成回答忠实度、引用正确率与引用覆盖率评测。
- [ ] 对比原问题、Query rewrite、动态 `topK`、阈值和字段过滤。
- [~] PREP 已将 Skill、简历/JD、题目 Rubric 派生成会话能力计划，前端展示计划能力点与证据清单；同一简历与 Skill 的最高三项历史训练任务作为受控“优先复测”输入，实际题单能力点精确匹配时会提升下一场优先级，且记录优先级能力点数。POST 的降级任务及模型汇总重复任务均会按能力点合并，保留全部关联题号，5 表示最高优先级。下一步：用固定跨场序列验证弱项复测覆盖率、重复题率和评分改善。LIVE 仍不执行重型 RAG。
  - [x] `retest-sequence-v1` 冻结 4 组跨场 PREP 场景（全命中、部分命中、历史去重、无历史）；计划现显式返回并记录 `retestCoverage = 实际复测能力点 ÷ 历史重点能力点`。它验证计划覆盖，尚不代表 LLM 出题或评分改善。
  - [x] LIVE 默认仅观察：`live-follow-up-v1` 冻结 5 组关键点、回答长度、能力点相关性和重复题检测场景；提交答案会返回建议并记录追问相关率/重复题数，但不会自动跳题或声称自适应训练有效。

进入 M3 的条件：正常回答至少有一个可核验来源，无来源时明确拒答，固定评测集可重复运行。

### M3：实时 Turn 状态与 Fake Provider

- [~] 已抽取 `VoiceTurnCoordinator`、统一事件与 `VoiceTurnOutboundWriter`；后者把浏览器可见 sequence 分配与实际 WebSocket 写出放在同一临界区，40 路虚拟线程替身测试验证实际写出严格为 1—40。`VoiceSessionContext` 尚未抽离。
- [~] 回复侧已实现 `THINKING → SPEAKING → COMPLETED`，支持 `CANCELLED/FAILED`；录音和提交状态仍沿用现有 SessionState。
- [x] 回复事件统一携带 `sessionId/turnId/eventId/sequence/eventType/createdAt`。
- [x] LLM 流、句级 TTS 和有序音频回调在写出前校验当前 Turn；取消或新 Turn 后会丢弃旧结果并计数。
- [x] 前端提供显式 cancel，服务端返回 cancel ack，并记录服务端确认耗时及用户/断连取消原因；取消请求携带随机 ID，浏览器收到同 ID 确认后仅一次性上报本地往返时延，服务端只消费当前 ID 以拒绝重放。开场题也进入同一 Turn 协议，避免“停止回复”仅停本地播放而没有服务端确认。客户端按每个 Turn 的 `sequence` 丢弃重复或迟到帧，20 次 Fake Provider 取消确认均校验元数据并受本地 P95 < 200ms 门槛保护。
- [~] 实现 Fake ASR/LLM/TTS，覆盖超时、重复 final、乱序、空音频、断连和迟到返回。已将 ASR final 缓冲抽为独立组件，固定测试覆盖重复 final、扩展 final、迟到 partial 与跨轮清理；真实 Handler 的 Fake Provider 实验已验证空音频不发送 audio、取消后迟到 TTS 不泄漏旧音频、第二句先合成完成时音频仍按句子序号发送、ASR append 断连后重连并重投当前帧、取消后迟到 LLM token/sentence 不写出且不触发 TTS、TTS 超时后 Turn 可结束且不发送半成品音频。新增开场题取消实验验证迟到音频不下发、匹配浏览器确认只记录一次。2026-09-20 本地真实浏览器开场取消得到服务端确认 2.184 ms、浏览器匹配确认往返 8 ms（各 n=1），仅为协议冒烟样本；下一步仍是同配置真实 20 Turn 对照。

进入 M4 的条件：不使用真实 API Key 也能稳定复现并验证取消、超时、乱序、断连和迟到结果。

### M4：实时性能

- [ ] 在 M0 基线和 M3 测试保护下实验二进制音频帧，并保留协议兼容窗口。
- [ ] 只有数据证明建连是长尾来源时，才实现有界 TTS 连接复用。
- [ ] 分句 TTS 采用有限并发、有界重排缓冲和按 sequence 发送。
- [ ] 用同一负载比较 P50/P95/P99、整轮 P95、网络字节与错误率。

完成门槛：取消和断连测试仍通过，音频无乱序，性能结论来自前后对照数据。

### M5：由证据触发的可靠性增强

- [ ] 仅在指标、实验或真实需求证明必要时选择：VAD、barge-in、断线恢复、Dead Letter、Outbox/Inbox、混合召回或 PREP/POST LangGraph。
- [ ] LangGraph 不进入实时 Turn 主路径。

## 本周期明确不做

- 不同时引入 RabbitMQ 和 Redis Stream。
- 不拆 Java/Python 微服务，不为技术名词引入多智能体。
- 不在基线前宣称性能提升，不在来源和测试集前宣称 RAG 更准确。
- 不把通用知识库聊天继续扩成与面试流程无关的独立产品。
- 不在 M0 期间并行推进 SaaS 租户、支付、Avatar、多语言和完整求职工作台。
