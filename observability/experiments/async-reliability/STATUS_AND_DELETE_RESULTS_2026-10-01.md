# 异步可靠性：状态写入、删除竞争与停止恢复

**决定：保留本轮已复现并验证的正确性修复；持续目标仍 active，S4 尚未完成。7 项受控检查通过不等于六类故障全部验收。**

## 1. 两个真实缺陷的前后对照

测试使用隔离 PostgreSQL/JPA/Redis，实际运行生产消费者、向量服务、仓储和事务执行器。VectorStore 用确定性替身控制故障时点，写入 1024 维测试向量，不调用供应商模型。因此本报告证明数据库/消息控制流程，不能作为 Embedding 质量、模型耗时或生产可靠性比例。

| 同一个受控故障 | 旧实现 A | 修复 B |
| --- | --- | --- |
| Embedding 期间父文档被删除 | 文档不存在，却提升出 1 个正式向量 | 正式/临时向量均为 0 |
| 数据库拒绝所有状态更新 | 文档停在 PENDING，仍生成 1 个正式向量并 ACK；Pending=0 | 不继续 Embedding；文档仍 PENDING，保留 Pending=1 |

原始两项 A 都失败；同样两项 B 都通过。A 的源文件及用例冻结在 `runs/status-delete-20261001/old-sources/`，断言、实际状态和队列输出分别在 `a-test-r1.xml`、`b-test.xml`。首次测试上下文的字段命名错误另保留在 `setup-failure.xml`，不算作业务缺陷。

## 2. 实现与评审

### 状态写入失败

`VectorizeStreamConsumer.updateVectorStatus` 不再吞掉持久化异常。异常交给现有模板重试；若重试或最终 FAILED 状态也写不入，调用失败会返回消费循环，原消息保持 Pending。数据库恢复后仍有消息可以认领，不会出现数据库停在 PENDING、队列却已确认的静默缺口。

这不提供数据库与 Redis 的分布式事务；首次入队前的进程崩溃、版本隔离和持久化 outbox 仍需后续故障实验。

### 向量提升与删除

`VectorRepository.lockExistingKnowledgeBase` 在提升事务中执行父文档 `SELECT ... FOR UPDATE`，并拒绝在事务外调用。`KnowledgeBaseVectorService` 先锁定并检查父文档，再删除旧正式向量、提升新 job；若父文档已删除，只清理本 job 的临时向量并记录 SKIPPED。

Embedding 和分批写入仍在提升事务外。真正持有父文档行锁的范围只覆盖最后的检查、替换或清理，不包含外部模型调用。并发删除检查记录实际 JDBC `autoCommit=false`，并在 PostgreSQL 中观察到另一个删除连接等待 `transactionid` 锁；提升事务提交后，删除及向量清理完成。

行锁的语义依据 [PostgreSQL 16 官方文档](https://www.postgresql.org/docs/16/explicit-locking.html)。JPA 与 JDBC 共享连接的适用条件按 [Spring JpaTransactionManager 文档](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/JpaTransactionManager.html)核对，最终以本实验的物理连接与锁等待观测验证。

### 消费者停止

`AbstractStreamConsumer.shutdown` 改为发出线程中断。停止期间发生业务异常时，按生命周期状态保留原 Pending；不依赖线程中断标记，因为 `InterruptedException` 可能清掉该标记。批内尚未开始的消息也保留 Pending，不再启动后续业务。

追加检查发现只检查线程标记会丢失恢复入口，已保留失败记录 `b-final.xml`。最终复验在同一批放入两条消息：中断第一条的 Embedding 等待，第二条尚未执行；两条均保持 Pending，未重新入队、无正式向量。

这只证明受控的可中断等待，不保证任意供应商 SDK 都响应中断，也不能替代实际进程崩溃实验。

## 3. 最终七项检查

`b-final-r2.xml`：7 项，0 失败/错误/跳过。所有检查均使用真实隔离 PostgreSQL/JPA/Redis；模型替身的使用范围保持一致。

| 检查 | 实际结果与限制 |
| --- | --- |
| Embedding 中删除父文档 | 正式/临时向量为 0；是控制时点实验 |
| 所有状态写入失败与恢复 | 1 次原投递加 3 次有界重试，保留 Pending；移除数据库故障后，认领并完成，Pending=0 |
| 重复投递 | 两条消息、一个活动消费者；一次替身 Embedding、一个正式片段，两个消息都 ACK |
| 提升期间并发删除 | 实际锁等待已观测，提交后删除完成、无残留正式向量 |
| 首次入队失败 | Redis ACL 真实拒绝 XADD，文档持久化为 FAILED，错误含 NOPERM |
| 重试入队失败 | 替身 Embedding 失败后，Redis ACL 真实拒绝重试 XADD；FAILED 持久化后原消息 ACK |
| 停止执行与未执行的批内消息 | 两条 Pending 均保留，未重新入队、无正式向量 |

超时认领使用真实 Redis XCLAIM/XAUTOCLAIM，但由测试只推进本消息的 idle 值到配置阈值以上。没有测量真实等待五分钟的恢复耗时。Redis 的 Pending/ACK/认领语义见 [XACK](https://redis.io/docs/latest/commands/xack/)、[XAUTOCLAIM](https://redis.io/docs/latest/commands/xautoclaim/)。

锁等待检查最初因当前事务缓存 `pg_stat_activity` 快照而失败。按 [PostgreSQL 统计文档](https://www.postgresql.org/docs/16/monitoring-stats.html)，每次观测前调用 `pg_stat_clear_snapshot()`；这只刷新测试会话的统计快照，没有修改生产监控配置或重置统计计数。

## 4. 六类故障矩阵的真实进度

| 原目标中的故障 | 状态 |
| --- | --- |
| 重复投递 | 已验证单活动消费者下的受控重复；多活动消费者竞争未验证 |
| ACK 前崩溃 | 未完成真实进程崩溃；优雅停止不能替代 |
| 超时认领 | 已验证真实命令及控制流程，idle 由实验推进；无恢复速度成绩 |
| 入队/重试失败 | 已验证真实 Redis 拒绝及可见 FAILED；跨数据库/队列提交的崩溃窗口仍需处理 |
| Embedding 中删除 | 已验证受控删除及并发父文档行锁；完整产品删除路径仍需验收 |
| 旧版本任务迟到 | 未完成；当前父文档行锁检查存在性，没有任务版本隔离 |

此外仍有明确的工程缺口：

- 重试入队失败后，公共模板仍把任务计数记为 RETRY，虽然数据库终态是 FAILED。需要把重试结果返回给模板并修正观测口径。
- 当前 Stream 使用 MAXLEN 裁剪；更大 backlog 下可能删除未确认消息的正文。Redis 7 的认领能报告已删正文，却不能恢复正文；不能据此保证任务永不丢失。最新 Redis 的 ACKED 裁剪选项始于 8.2，当前隔离运行的是 Redis 7，不能直接照抄。[官方 XADD 文档](https://redis.io/docs/latest/commands/xadd/)
- 其他业务消费者的状态写入、多个工作进程并发和完整文字/语音/知识库产品路径尚未在本组实验验收。

这些缺口继续纳入 S4，不因为本轮检查通过就收缩目标。

## 5. 回归、清理与复跑证据

按公共后端规则重新执行 `:app:test --no-daemon --rerun-tasks`：默认后端 300 项，64 个套件，0 失败/错误/跳过。该默认任务排除 integration 标签；本报告的七项集成故障检查是另一次明确选择的运行，不代表其余集成组或完整产品路径都通过。

清理核验：实验新建文档、故障触发器、临时向量、Pending 消息和实验 ACL 用户均为 0。四篇公开资料原始 24 块的 ID/内容/元数据/向量整体指纹与先前备份完全相同，没有重建它们的索引。

运行目录：`runs/status-delete-20261001/`。

- `comparison.json`：A/B、最终七项、默认回归、清理和未完成项。
- `a-test-r1.xml`、`b-test.xml`：相同两项故障的原始对照。
- `b-final-r2.xml`、对应 invocation/source-hashes：最终七项及本次源码指纹。
- `candidate-sources/`、`backend-tests/`：冻结候选源码与默认测试 XML，短文件名索引及哈希。
- 首次设置、编译及后续失败记录全部保留。`b-extended.xml` 是初版采集器在编译失败后误复制的旧文件，**不得评分**；其 sidecar 已明确标记。采集器现只接受本次运行新写出的 XML，汇总工具要求本次 exitCode=0、无跳过，且默认回归 XML 晚于当前 Java 源码。

复跑：先停止已确认的隔离实验应用，再执行 `observability/experiments/async-reliability/run-fault-tests.ps1 -Label <新的标签>`。必须使用现有隔离端口/数据库；脚本只从忽略目录读数据库凭据，日志不输出凭据值。选择 integration 标签及指定测试类，不启动完整应用，也不调用外部模型。汇总工具默认拒绝覆盖已冻结 comparison；新轮次应使用新实验目录/版本。

本轮没有语音性能、检索召回或生成忠实度的新分数，不把故障替身测试数写成简历准确率或生产稳定性。
