# 文字会话删除缓存实测

## 缺陷、修复与采用

Controller 单条删除和简历批量删除均调用 InterviewPersistenceService，旧实现只删除数据库。getSession 优先返回 Redis，形成已删除记录继续可读的问题。

保留修复：同一持久化 Service 在成功删除后登记事务提交回调，提交后清理准确的会话 key 及缓存已有的简历映射；回滚不失效，数据库失败不登记。批量删除只清理本次查到的 sessionId，不循环查库。

## 冻结对照与实际执行

- 旧源码行为基线：本次筛选命中 2 个删除测试类，实际 13 tests，其中新增文字边界 7 tests 的 3 项缓存失效断言失败；另外 6 项是既有语音删除测试。不是编译失败。
- 新源码完整后端：`--rerun-tasks :app:test` 实际 92 suites / 461 tests，全部通过、0 skipped。该 Gradle 任务排除 integration 标签，不把它写成包含 H2 集成测试。
- H2 独立集成：`--rerun-tasks :app:integrationTest --tests '*InterviewTrainingHistoryIntegrationTest'`，实际 1 suite / 7 tests，全部通过，包括新加的提交、回滚和同简历批量删除三项。
- 真实隔离 App、PostgreSQL、Redis、REST GET → DELETE → GET：旧/新各一个公开技术题的受控 SQL seed，输入文件字节相同。旧实现 DB 1→0、缓存 1→1、删除后仍可读；新实现 DB 1→0、缓存 1→0、删除后不可读。
- 两个真实接口运行前后范围均恢复：15 个公开文档、123 个向量，文字/语音/RAG 会话与消息均为 0；原公开索引校验不变。只清理各自随机 seed 返回的 sessionId/key；Token 计数前后相同，外部模型调用 0。

## 证据与边界

冻结运行位于 `observability/experiments/voice-frame-pipeline/runs/`：

- `business-persistence-cache-baseline-20261005-r1`
- `business-persistence-cache-candidate-20261005-r1`
- `business-persistence-cache-integration-20261005-r1`
- `text-delete-live-baseline-20261005-r1`
- `text-delete-live-candidate-20261005-r1`

实际接口记录由受控 SQL seed 创建，本轮没有测试模型出题或 POST 创建会话。H2 的简历是测试生成的标识，不是实际简历/JD。证据不证明解决了所有并发回填或 Redis 故障场景，不作为响应速度/模型质量或生产规模的指标。

语音报告原子提交、完整产品流程、正式语音浏览器与 RAG 检索/生成/引用/成本门继续推进。总目标 active，未以本组缓存修复代替完整 S0–S4。
