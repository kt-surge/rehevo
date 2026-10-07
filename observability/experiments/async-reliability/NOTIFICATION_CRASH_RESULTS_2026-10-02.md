# 持久化恢复补充门：真实入队后退出与实际调度

## 结论

两项新增真实基础设施门通过，生产代码与上一批候选保持一致；没有为了通过新增门改生产行为。默认持久化恢复继续关闭。真实多消费者执行权与持久化预算仍需实现，本轮结果不能扩大为并发去重或模型调用恰好一次。

原始目录：[durable-notification-crash-20261002](runs/durable-notification-crash-20261002/)。本批是候选协议补充检查，不是新的性能 A/B；上一批公平 A/B 与首次失败日志保持原样。

最终封存 111 个成果，哈希差异 0、已读取敏感值命中 0；实验父文档、任务行、临时向量、故障触发器/schema、PEL 与消费组 lag 均为 0。独立后端恢复 TOKEN800/健康 UP，原四篇资料索引指纹不变。首次成果采集器误按数组解析 Redis JSON 对象而停止，修正采集器后通过；未修改生产或实验用例，错误记录一并保留。

| 检查 | 方法 | 结果及范围 |
| --- | --- | --- |
| Redis 入队后、记录投递前进程退出 | 独立 JVM 调用实际生产者；实际 XADD 返回成功，任务尚无消息 ID/投递次数，再 `halt(75)` | 退出码 75、事务外、已接受快照保留；重通知后有 2 条相同 generation/正文通知，单消费者调用一次 Embedding 替身，COMPLETED，PEL/积压为 0 |
| 实际定时扫描自动恢复未入队快照 | 实际任务接受；Spring `@EnableScheduling` 激活生产 `@Scheduled` 方法，测试不调用 recoverOnce | 自动产生 1 条通知、一次 Embedding 替身调用、任务/文档 COMPLETED；实验扫描间隔 100ms，重通知等待 30s |

第一项显式调用实际 `recoverOnce` 制造重复通知，再由单消费者消费；第二项使用真实 Spring 调度处理器，激活的是受控上下文，不是完整 App 重启。100ms 是实验配置，观察窗 12 秒；未测量真实恢复延迟或生产吞吐。外部模型全为确定性 1024 维替身。

新增类 `VectorTaskNotificationIntegrationTest` 仅运行 `notification*` 两项方法，避免把继承的旧故障方法重复计入。新的完整默认后端 fresh run 303 项、65 套件，0 失败/错误/跳过；此前 8 项持久化协议、9 项故障、6 项版本协议及 2 项竞争证据仍在上一批封存目录。

## 自审与后续

本次为执行 Agent 自审，没有独立人工或其他 Agent 审核。仅单消费者的结果不足以允许默认开启。下一门见 [D2 执行租约方案](VECTOR_EXECUTION_LEASE_DESIGN_2026-10-02.md)：

1. 当前候选作为 A，使用两个实际消费者，复现同版本通知重复计算。
2. B 加入执行 owner/fence/租约与心跳，在外部计算前消耗持久化尝试预算。
3. 在正式索引/COMPLETED 原子提交与失败写回中核对有效执行凭据；实际父锁等待跨越租约时也必须拒绝失权提交。
4. 覆盖旧成功/失败、过期者续约、裁剪/重通知不重置预算、外部调用中退出、删除与重向量化取代。

数据库时间及调度方案已核对官方文档，具体选择与推论记录在 D2 方案；实现与数据尚不存在，不能写入简历成果。总目标中的实时语音及 RAG 检索/生成效果任务继续保留。

## 封存

`collect_notification_crash.py` 检查新用例原始结果、实际退出/调度观测、上批生产源码与冻结证据不变、默认完整回归、原索引和实验清理；生成 [comparison.json](runs/durable-notification-crash-20261002/comparison.json)、[成果哈希](runs/durable-notification-crash-20261002/artifacts.sha256.json) 与 [封存核验](runs/durable-notification-crash-20261002/post-freeze-verification.json)。使用新的实验目录或 label 复跑，不覆盖已封存文件。
