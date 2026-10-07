# Rehevo 完整目标接续：2026-10-06

本目标轮判定为 **progress**。整体沿用原 S0–S4 和原完成条件，active；没有需要用户补信息才能继续的外部阻塞。前一阶段缓存/报告修复的外部模型调用为 0；最新参考事实诊断为同 Flash 4 对/8 个观测 Chat 操作、27681 Token，跨模型操作 0。此前恰好八次的跨模型授权没有追加使用。

## 已完成的实际修复

1. [文字删除缓存](../interview-adaptation/TEXT_DELETE_CACHE_RESULTS_2026-10-05.md)：单条/简历批量删除在事务提交后清理准确缓存。旧受控 REST 删除后 DB=0、Redis=1、GET 仍可读；新 DB=0、Redis=0、GET 不可读。旧筛选 13 tests 中 3 failed；新完整后端 92 suites / 461 tests 通过，独立 H2 7 tests 通过。
2. [语音报告提交与轮询](../interview-adaptation/VOICE_REPORT_COMMIT_RESULTS_2026-10-06.md)：独立事务 Service 重新锁定会话并原子提交报告/COMPLETED，删除共用父记录锁，重复完成重放不再评估，轮询直接查数据库。同一 6 项 H2 用例旧 6 failed、新全部通过；另最新完整后端 92 suites / 461 tests 全通过、0 skipped。
3. 真实 PostgreSQL/Redis/REST 空语音评估：旧 session39 的 DB 已完成但 GET 不可见；新 session40 经真实创建、Stream 消费、状态轮询可见报告，删除后 DB/缓存无残留。无 WS、ASR/TTS 或真实模型评分，不作为完整语音质量/性能验收。

两组各 5 个冻结版本，共 415 个文件独立重算 SHA-256 与准确成员均匹配，已读取密钥原值扫描无命中。此前封存成果不修改。公开索引 15 文档/123 向量，原四篇/24 块指纹不变；文字/语音/RAG 会话与消息、语音报告及文字答案归零。最新隔离 App 句柄 39512，PID37364、18080、health UP，当前已测类文件早于进程启动；后续停止前需实时验证所有权。

## 最新参考材料修复与证据

详见 [事实、正常参考预算与同模型诊断](../interview-adaptation/REFERENCE_FACTS_RESULTS_2026-10-06.md)。修复 Spring 事务/Boot 覆盖、MySQL 索引/锁、MQ 事务/确认的事实条件。r1 扩写版的四对真实输出仍有内容风险，质量 HOLD；不能把这批结果归到最终压缩版 r4。发现并撤回扩写造成的正常评估覆盖回退，最终 r4 在原预算下完整加载 5970 字符，系统设计正文恢复为 1539 字符（旧版1196，r1仅74）。这是生产加载方法的离线材料完整性，不是 RAG 或评分效果指标。

最终新鲜后端回归 92 suites / 461 tests 全通过、0 skipped，不包括独立 integrationTest。5 个材料版本、8 个真实生成、4 个回归/加载版本共 17 个目录、2878 个文件已封存，独立精确成员与 SHA-256 均匹配；现有隔离 DB 的 Chat 密钥仅在内存用于扫描，无明文文件/命中。工具缺失可选 providers.env 的失败、首次重启日志占用失败均留存并恢复。证据为 `../interview-adaptation/reference-facts-seal-verification-20261006.json` 与 `reference-facts-phase-status-20261006.json`。

## 接续与保留门

- 补已有 PROCESSING 加报告的恢复、并发状态更新、Redis 故障与消费者完成/ACK 崩溃边界。受控 H2 和单场 REST 不替代完整异步故障矩阵，也不证明严格一次外部模型调用。
- 接续最终 r4 与旧材料的固定问题/正确错误回答评分预检，计划和输入已冻结（`REFERENCE_FIXED_SCORING_PLAN_2026-10-06.md`，实际调用 0），预期标签不提供给模型。开发诊断不替代未用于调参的新正式测试；训练目标关联、分类和参考完整性均不等于出题/评分质量通过，当前仍 HOLD。
- RAG 分别测检索必要证据与生成忠实度、必要事实、引用支持、拒答及实际 Token/成本。标识正文选择仍为实验性；成本区间与新资料生成门未通过不得默认启用。已曝光问题仅做开发回归。
- 语音帧流、连续播放及 Skill 预加载仍默认关闭。正式浏览器至少 100 有效样本/组、至少 3 个窗口，以及原 P95/P50/错误/顺序/晚到音频/资源/间隙门保持；先过问题质量门再扩样。
- 验收受控 Skill/简历/JD 到文字/语音评估、训练回流和文字复测的完整产品流程，交付三条真实简历表述。区分组件性能、历史评测、小样本与最终收益。

状态证据位于 `../interview-adaptation/business-persistence-phase-status-20261006.json`；封存核验分别为 `text-cache-seal-verification-20261006.json` 与 `voice-commit-seal-verification-20261006.json`。完整目标尚未完成。
