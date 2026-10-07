# 语音评估报告提交与故障对照

## 实际链路

消费者先置 PROCESSING → VoiceInterviewEvaluationService 在事务外调用统一评估 → 同类内部调用标注事务的方法保存报告 → 消费者另外写 COMPLETED → ACK。正常报告每次 new entity，sessionId 唯一；空报告已有 upsert。会话删除先检查 exists，再删除报告/消息/会话。报告表 sessionId 只是 Long，不是 JPA 外键关联。

因此报告和完成状态之间存在崩溃窗口；正常报告重试可能撞唯一约束。评估期间删除会话，旧保存方法仍用起始会话快照，可能写孤立报告。评估 HTTP 轮询读取会话缓存，可能看不到数据库最新状态。

同类调用不会经过 Spring 默认事务代理；Repository.save 自身有事务，但不足以使报告与完成状态原子提交。参考：[Spring 事务代理](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)、[Spring Data JPA 锁](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)。

## 方案与评审

新增独立持久化 Service：公开事务方法内重新锁定会话、查同会话报告、upsert 报告并同时置 COMPLETED/清错误。正常与空结果共用事务。已完成且有报告的重放不覆盖、不再调用模型；删除后的提交返回 SKIPPED。生成调用和引用构建仍在事务外，锁只覆盖 DB 提交。

删除操作在读子记录前锁同一父会话；这样报告先提交则删除可以看见并删除报告，删除先提交则保存重新检查到会话缺失。只有参与本协议的写入被覆盖；不宣称任意 SQL 写入或其他会话方法已满足并发一致性。

消费者完成钩子不再二次更新数据库；任务失败仍沿用原模板和失败恢复边界。成功提交后调用既有精确会话缓存失效回调；评估 GET/POST 状态读取直接查数据库，以数据库作为任务状态依据。Redis 故障与完整异步矩阵仍单独验收。

先冻结旧实现，H2 真实代理事务 + 受控模型替身，不发送任何外部模型请求：空结果完成、重复投递、生成期间删除、状态写入约束故障回滚、事务外模型与提交时元数据、缓存旧状态轮询。旧/新使用同一受控报告和输入；保留失败 XML。候选完整后端测试和独立集成测试后，继续真实隔离 PostgreSQL/Redis/REST 的受控非模型流程。此组不证明评分质量或语音端到端时延。
