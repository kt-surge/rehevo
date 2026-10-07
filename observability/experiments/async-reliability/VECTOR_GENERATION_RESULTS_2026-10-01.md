# 向量请求版本隔离：实现与真实对照结果

## 1. 本轮决定

保留持久化请求版本、带版本的条件状态写入，以及正式向量替换与完成状态的原子提交。它们修复两个实际复现的竞争问题：旧任务迟到时覆盖新索引，以及旧任务失败时把新任务的完成状态改成失败。

真实隔离 PostgreSQL/JPA/Redis、确定性 VectorStore 的相同竞争用例：**A 两项失败，B 两项通过**。另有六项版本/事务/迁移检查和原有九项故障回归通过。完整默认后端重新执行 **303 项、65 个套件，0 失败/错误/跳过**。

额外用实际 HTTP 入口、RustFS/Tika 和现用真实 Embedding 完成了一份独立短文档的上传、重新向量化、下载、检索与删除检查。这是产品接线验收，没有检索质量或响应速度的 A/B 结论。

**S0–S4 整体目标继续 active。** 数据库提交到 Redis 入队的缺口、队列裁剪后的恢复、多工作进程执行租约、删除失败补偿及完整面试路径尚未完成。真实语音与生成质量对照仍受同模型额度限制。

## 2. 同一竞争案例的 A/B

两名活跃消费者使用实际生产消费者和向量服务。旧请求在 Embedding 替身中被屏障暂停，新请求由第二名消费者先完成，然后释放旧请求。每项同时检查正式向量内容、ID、请求版本、数据库状态和 Pending。

| 场景 | A：本轮改动前 | B：版本隔离后 |
| --- | --- | --- |
| 旧请求成功迟到 | 正式内容被旧请求覆盖，新请求向量 ID 丢失 | 新请求内容和 ID 保留，COMPLETED，Pending=0 |
| 旧请求失败迟到，重试次数已达上限 | 新索引仍在，但父文档被写成 FAILED | 新索引及 COMPLETED 保留，Pending=0 |
| 相同两项断言 | 2 失败 | 2 通过 |

A/B 竞争测试源码哈希完全相同。两组均由实际 SQL 分配请求标记；A 忽略新增可空字段和消息字段，B 读取它们。因此这组验证的是生产消费/提交竞争，**不是完整 HTTP 重新向量化的并发 A/B**。完整 HTTP 接线另列在第 5 节。

原始证据：[A XML](runs/vector-generation-20261001/a-race.xml)、[最终 B XML](runs/vector-generation-20261001/b-race-final.xml)、[对照汇总](runs/vector-generation-20261001/comparison.json)、[A 源码索引](runs/vector-generation-20261001/old-sources/file-index.json)。

## 3. 实现机制与审查

### 请求版本与执行批次分开

`knowledge_bases.vector_generation` 保存最近接受请求的 UUID。上传及重新向量化先在短事务中分配版本、写 PENDING，再在事务外发送 Redis 消息。消息和重试沿用同一个请求版本；每次实际向量化另分配临时 `jobId`，避免重试互相清理临时片段。

PROCESSING/FAILED 通过 `id + generation` 条件更新，返回实际影响行数；已经 COMPLETED 的同版本不再改写。消费者在开始、异常处理及重试前检查删除/过期/完成，过期请求记 skipped，不再入队旧版本。

普通 JPA 元数据保存不能回写任务字段：`vectorGeneration/vectorStatus/vectorError/chunkCount` 的 ORM UPDATE 被禁止，这些字段使用明确的条件更新。真实 JPA 旧实体保存实验已验证，名称可更新，当前版本、状态和块数保持不变。注解控制范围依据 [Jakarta Persistence Column 文档](https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/column)。

### 正式向量和完成状态原子提交

Embedding 分批写入临时向量，整个外部调用过程不持有数据库事务。提升时短事务先锁父文档，重新读取版本；仅匹配当前请求且尚未完成时，才替换原索引并写 COMPLETED/chunkCount。

提升数量必须等于预期块数，完成状态必须实际更新一行；任何一步失败，整个替换事务回滚。旧版本或已删除任务仅清理自己的 jobId，不删除新请求结果。行锁范围依据 [PostgreSQL 16 锁说明](https://www.postgresql.org/docs/16/explicit-locking.html)，实际结果以本轮故障记录为准。

### 公共消费者契约

状态写入和业务处理返回实际结果，区分完成/跳过；重试返回 ENQUEUED/FAILED/SKIPPED。模板只按这些结果计数。简历、文字评估和语音评估同步适配返回值，仍保留状态异常传播。

ACK 失败目前会记录异常并依赖 Pending 回收；业务成功计数不能解释为端到端投递成功率。新生产者当前调用点均在外层事务之外，尚未增加对未来外层事务调用的统一拒绝或提交后调度保护。

## 4. 六项版本协议检查

| 检查 | 实际观察 |
| --- | --- |
| 新旧消息乱序 | 实际生产者生成两个版本；旧消息先到仍 skipped，仅一次 Embedding 替身调用；最新请求完成 |
| 失败重试 | 两条实际 Redis 消息版本相同，第二条 retryCount=1；两次替身尝试后完成，正式向量 1 |
| COMPLETED 写入被真实数据库触发器拒绝 | 四次尝试后 FAILED；每次新索引提升均回滚，原向量 ID/内容保留，临时片段和 Pending 清零 |
| 旧 JPA 实体保存元数据 | 名称保存成功，当前 generation/PROCESSING/chunkCount=17 不被旧实体覆盖 |
| 无版本的未完成历史消息 | 保持 Pending=1；显式新请求完成后，旧消息通过生产回收路径确认，Pending=0 |
| 前向/回滚迁移 | 未完成旧数据阻断前向并回滚新增字段；完成旧数据兼容且可重复执行；未完成状态阻断回滚，完成后可撤销字段 |

迁移和回滚检查使用自己创建的 PostgreSQL schema。公共实验表仅执行前向迁移，四条旧 COMPLETED 文档的 generation 仍为 NULL，没有重做 Embedding。旧消息实验推进了测试消息 idle，不能当作恢复延迟。

[六项最终 XML](runs/vector-generation-20261001/b-contract-final.xml)、[显式迁移及回滚说明](../../../docker/postgres/migrations/README.md)。

原有九项故障回归全部重新执行，包含删除竞争、状态拒绝、重复消息、父文档锁、首次/重试入队拒绝、停止保留 Pending、双重写入失败恢复，以及真实子 JVM 在完成提交后、XACK 前 `halt(73)`。后者重启后额外 Embedding 替身调用为 0、向量 ID 不变、Pending 清零。这些检查仍使用确定性 VectorStore，不是模型服务可用率。

[九项 XML](runs/vector-generation-20261001/b-faults.xml)、[实际子进程恢复](runs/vector-generation-20261001/b-faults-child/recovery.json)。

## 5. 实际产品入口检查

独立文件 SHA-256 为 `a61d0971e6e69d1ef4436b148c2b88ee52cb8fdd009d5438e950784082aebcc9`，文档 ID=102，分类为本实验专用。它是人工构造的短测试材料，不加入 Gold。

1. 实际 multipart 上传，经 RustFS/Tika、生产者和消费者完成现用 `qwen3.7-text-embedding` 的真实 1024 维向量化，正式块 1。
2. 通过实际下载入口核对存储文件字节，SHA-256 与上传一致。
3. 实际调用 revectorize，生成不同请求版本；第二次向量化完成，正式块 1，旧向量 ID 被替换。
4. 实际 HYBRID 检索，rewrite=false，不调用 Chat；返回一条证据，其 ID 只属于第二次请求的正式向量。
5. 实际删除 API 成功；独立查询确认自己的父文档、正式和临时向量均为 0。

删除后的 S3 对象缺失未做独立 HEAD 检查，因此这里只确认下载前字节正确及删除后的数据库/向量状态。现有删除服务对存储清理失败仅日志提示，向量删除也有吞异常路径，仍需要故障复现和持久化补偿。不能把这次正常路径成功写成删除可靠性已完成。

[真实入口原始结果与限制](runs/vector-generation-20261001/api-smoke/summary.json)、[检索响应](runs/vector-generation-20261001/api-smoke/retrieval.json)。

## 6. 默认回归、失败记录与成果复核

第一次完整默认回归执行 303 项，1 项失败：空内容业务异常已经按规则保留原始消息，但既有测试仍要求通用包装文案。只修正测试断言为业务错误码/明确消息，并检查不写入或替换向量；随后强制重新执行全部 303 项，通过。两次日志及失败 XML 均保留，没有把第一次结果删除。

[初次失败日志](runs/vector-generation-20261001/backend-fresh.log)、[修正后完整日志](runs/vector-generation-20261001/backend-fresh-r1.log)、[最终默认 XML 索引](runs/vector-generation-20261001/backend-tests/file-index.json)。

首次成果清单在收集器最后一行 stdout 写入前捕获了空 `collector.log`。初始清单保持原样；仅这一项差异被核对为收集器的预期汇总输出，再创建最终清单。未改动 A/B、XML、业务源码或对照结果。初次校验助手还误认为可选 Provider 文件一定存在；最终校验助手按实际文件存在情况读取凭据来源。

最终 **258 个封存成果哈希，0 不一致；当前冻结源码差异 0；已读取敏感值在 UTF-8/UTF-16 成果中命中 0**。使用最终清单复查，不能把初始清单误称为全部校验通过。[补封存说明](runs/vector-generation-20261001/artifact-finalization.json)、[最终清单](runs/vector-generation-20261001/artifacts-final.sha256.json)、[复核结果](runs/vector-generation-20261001/post-freeze-verification.json)。

本实验文档、触发器、schema、临时向量、Pending、ACL 用户均清理为 0。四篇公开资料原始 24 块的 UUID/文字/元数据/向量整体指纹保持一致。独立后端已经恢复 TOKEN800，健康 UP/HTTP 200，四份资料全部 COMPLETED，块数仍为 6/4/8/6。

## 7. 复跑与下一步

停止确认属于本实验的后端，使用新的 Experiment/Label：

```powershell
& observability\experiments\async-reliability\run-fault-tests.ps1 -Experiment generation-repeat -Label race -TestClass VectorGenerationRaceIntegrationTest
& observability\experiments\async-reliability\run-fault-tests.ps1 -Experiment generation-repeat -Label contract -TestClass VectorGenerationContractIntegrationTest -TestMethod 'generation*'
& observability\experiments\async-reliability\run-fault-tests.ps1 -Experiment generation-repeat -Label faults -TestClass VectorizeFaultIntegrationTest
```

实际入口烟测需要恢复独立应用后执行，输出目录必须全新；它会发起两次文档向量化和一次检索，使用真实 Embedding，不能当作无模型的单元检查。SDK 内部重试次数未独立统计：

```powershell
& D:\Anaconda\python.exe observability/experiments/async-reliability/run_generation_api_smoke.py --output observability/experiments/async-reliability/runs/generation-repeat/api-smoke
```

A 组须按保存源码在隔离目录复建。生产用户库迁移、未完成历史任务恢复清单及默认策略切换没有执行。

下一项采用[持久化任务恢复设计与自审](DURABLE_VECTOR_TASK_DESIGN_2026-10-01.md)：先复现入队前进程退出、裁剪丢正文和重复回收，再分开验证任务持久化、投递与执行租约。并行的语音/RAG核心目标继续保留，不用本轮可靠性正确性数字代替它们的效果数据。
