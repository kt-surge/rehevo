# 文字会话删除缓存：方案与对照

当前 DELETE Controller 直接调用 InterviewPersistenceService.deleteSessionBySessionId；按简历批量删除也调用同层。两个方法仅删 DB，不失效 InterviewSessionCache。getSession 优先从 Redis 返回，导致已删除会话仍可读。

在现有持久化 Service 注入缓存，仅在 DB 事务成功提交后删除精确会话 key 及该缓存维护的简历映射。单条与批量共用提交回调；回滚不删除、DB 失败不登记、无同步器直接调用按已完成删除处理。保持 Controller/Repository 分层和既有 Result/错误码。缓存操作不触发模型、文件或 HTTP。

基线先用隔离库中的一个公开技术题/自建随机 sessionId，经真实 GET 恢复 Redis，再经原 DELETE 和 GET 检查 DB/Redis/接口结果。此记录由受控 SQL seed 创建，明确不是模型出题或 POST 创建验收。只清理此次 seed 返回的 ID/key，原公开索引和零会话范围前后验证。

冻结旧/新源码、相同题目输入与请求顺序；提交/回滚/数据库失败/批量/无事务同步器边界做行为回归及 H2 真实提交检查。新实现必须完整后端测试，重新启动隔离 App 后复跑相同 REST 场景。可用 Redis 下的本场景修复不宣称解决所有缓存并发回填或 Redis 故障；这些故障与完整 S4 门仍需审查。
