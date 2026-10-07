# 异步重试观测与真实进程崩溃：本轮结果

## 1. 结论与保留决定

保留两项修复：重试指标按实际入队结果记录；简历、文字评估与语音评估不再吞掉状态写入异常。新增真实子 JVM 崩溃验证，确认当前“完成提交后、XACK 前退出”这一检查点可以在重启回收时跳过已完成业务并确认原消息。

本轮真实隔离 PostgreSQL/JPA/Redis 故障组 **9 项通过**；完整默认后端 fresh run **303 项、65 个测试套件，0 失败/错误/跳过**。三类其他消费者的新增回归使用仓储与传输替身，不称为其完整真实模型流程验收。

整体 S0–S4 目标仍未完成。旧任务版本隔离、多工作进程回收、消息裁剪/持久化恢复、完整产品路径及真实语音/RAG 效果仍需继续推进。

## 2. 同一案例的真实 A/B

真实 Redis 为本实验创建的 ACL 用户拒绝 XADD。VectorStore 使用确定性失败替身，不调用模型。原消息与状态通过实际 Redis、PostgreSQL/JPA 验证；A/B 的测试源码哈希相同。

| 观察 | A：原实现 | B：修复后 |
| --- | --- | --- |
| 重试 XADD | 被实际 Redis 拒绝 | 被实际 Redis 拒绝 |
| 文档终态 | FAILED，错误含 NOPERM | FAILED，错误含 NOPERM |
| 原消息 Pending / 实际队列消息数 | 0 / 1 | 0 / 1 |
| retry 计数 | 1 | 0 |
| failure 计数 | 0 | 1 |
| 同一回归断言 | 失败 | 通过 |

旧模板在调用 `void retryMessage` 后无条件计入 RETRY。四个消费者在入队失败后尝试持久化 FAILED 并返回，模板无法判断实际结果。现在返回 `ENQUEUED/FAILED`：只有实际 XADD 成功才记录 RETRY；失败终态已持久化才记录 FAILURE 并确认原消息。失败终态也写入失败时异常向外传播，原消息保留 Pending，不声称已重试或已终止。

简历与文字评估的状态辅助方法，以及 `VoiceInterviewService.updateEvaluateStatus`，原先捕获并仅记录数据库异常。现在异常交给公共模板处理，防止 PROCESSING 未写入仍继续调用模型，以及终态未写入仍 ACK。

原始记录：[A](runs/retry-crash-20261001/a-retry-metrics.xml)、[相同案例 B](runs/retry-crash-20261001/b-retry-metrics.xml)、[对照汇总](runs/retry-crash-20261001/comparison.json)。

## 3. 真实进程退出与恢复

父实验创建自己的文档和消息，再通过 Java 参数文件启动独立子 JVM。子进程使用生产消费者、向量服务、真实 JPA/事务/Redis；仅 VectorStore 为确定性替身，将 1024 维固定向量写入真实 pgvector 表。

子进程到达消息确认调用时，检查完成状态、正式向量及 Pending 都已存在，写入检查点后执行 `halt(73)`。没有执行真实 XACK，也没有执行 Spring/消费者关闭钩子。父实验验证实际退出码为 73，不能用启动失败代替崩溃成功。

| 检查项 | 结果 |
| --- | --- |
| 崩溃点 | COMPLETED 状态与正式向量已提交，真实 XACK 尚未调用 |
| 子进程实际退出码 | 73 |
| 崩溃后状态 / Pending | COMPLETED / 1 |
| 重启消费者后 Pending | 0 |
| 重启后额外 Embedding 替身调用 | 0 |
| 正式向量 ID | 与崩溃前相同 |
| 重启处理指标 | skipped=1 |

为了有界执行，只推进本实验消息的 Pending idle，再由生产 XAUTOCLAIM 路径回收。这验证恢复正确性，**没有测量实际五分钟超时或用户可见恢复速度**。本次也没有验证 Embedding 中途、临时向量写入中途等所有崩溃点，更不证明多个活跃消费者的 exactly-once。

Redis 的 ACK 将消息移出消费组的 Pending；XAUTOCLAIM 用于接管达到 idle 条件的未确认消息。业务完成检查承担本案例的重复处理抑制，恢复效果以以上原始实验为准。[XACK 官方文档](https://redis.io/docs/latest/commands/xack/)、[XAUTOCLAIM 官方文档](https://redis.io/docs/latest/commands/xautoclaim/)。

原始记录：[最终子进程检查点](runs/retry-crash-20261001/b-final-r1-child/checkpoint.json)、[父进程恢复观察](runs/retry-crash-20261001/b-final-r1-child/recovery.json)、[子进程日志](runs/retry-crash-20261001/b-final-r1-child/child.log)。

## 4. 最终故障组与默认回归

最终 `b-final-r1.xml` 中 9 项全部执行并通过：

1. Embedding 期间删除文档，正式/临时向量均不残留。
2. 所有状态写入被数据库触发器拒绝，Pending 保留；撤销故障后回收完成。
3. 同一活跃消费者处理两条重复消息，一次业务结果，两条均确认。
4. 向量提升事务持有父文档行锁，真实并发删除等待，删除完成后无孤儿向量。
5. 真实 Redis 拒绝首次入队，持久化 FAILED。
6. 真实 Redis 拒绝重试入队，持久化 FAILED，指标不误计 RETRY。
7. 停止正在等待 Embedding 的消费者，处理中与同批未处理消息均保留 Pending。
8. 完成提交后 ACK 前，真实子 JVM 退出；重启后无重复 Embedding，Pending 清零。
9. 重试入队与 FAILED 写入同时被拒绝，状态保留 PROCESSING、Pending=1、retry/failure 均不误计；撤销故障后恢复为 COMPLETED、正式向量 1、Pending=0。

第 9 项只拒绝自己的文档 FAILED 写入，其他操作仍使用真实 JPA/数据库。仓储委托包装仅通知父实验已经尝试终态写入，并不替代数据库故障。

默认后端另新增三项必要回归：简历、文字评估、语音评估的 PROCESSING 和重试终态都写入失败时，禁止业务模型调用和原消息确认。语音项使用真实状态服务。它们属于替身回归，未调用真实供应商。

## 5. 失败记录、冻结与清理

- `real-crash.log`：首轮在 Gradle 测试进程启动前失败，fresh XML=false，无崩溃结论。调整为 classpath 文件后，`real-crash-r1` 真正运行并通过。
- `b-final.xml`：9 项中 1 项因测试装置的 Mockito/Spring 代理解包冲突失败；其他 8 项通过。改用真实仓储委托，仅修复观测装置，随后完整重跑 `b-final-r1`，9 项通过。
- 原实现、候选源码、默认回归 XML、实际崩溃日志与失败尝试均保留；**125 个冻结成果的哈希核对为 0 不一致**。原源码与 A 组校验值相符；当前隔离运行环境的敏感值未发现进入这些成果。
- 实验创建的文档、触发器、临时向量、Pending、ACL 用户均已清理为 0；四篇公开资料原始索引的 UUID/内容/元数据/向量整体指纹保持一致。独立后端已恢复 TOKEN800，健康 HTTP 200，四篇资料全部 COMPLETED，块数仍为 6/4/8/6。[恢复记录](runs/retry-crash-20261001/runtime-restored-health.json)

核验依据：[冻结后复核](runs/retry-crash-20261001/post-freeze-verification.json)、[完整成果哈希](runs/retry-crash-20261001/artifacts.sha256.json)、[源码索引](runs/retry-crash-20261001/candidate-sources/file-index.json)、[默认回归索引](runs/retry-crash-20261001/backend-tests/file-index.json)。

## 6. 复跑与接续

使用当前项目独立实验基础设施。先停止已确认的本实验后端，再运行：

```powershell
& observability\experiments\async-reliability\run-fault-tests.ps1 -Experiment retry-crash-repeat -Label candidate
```

新运行必须采用新的 Experiment/Label，脚本拒绝覆盖现存结果。数据库凭据从本地忽略文件读取，不打印或写入成果。默认测试独立运行 `:app:test --no-daemon --rerun-tasks`。A 组需要按冻结源码在隔离工作目录复建，不能把保存结果或另一个策略结果冒充 A。

下一项见 [向量化任务版本隔离设计与自审](VECTOR_GENERATION_DESIGN_2026-10-01.md)：先复现实际重向量化竞争，再实施持久化请求版本、条件状态更新与原子提升/完成。该方案尚未实现，Redis 裁剪和持久化投递恢复也仍待处理。
