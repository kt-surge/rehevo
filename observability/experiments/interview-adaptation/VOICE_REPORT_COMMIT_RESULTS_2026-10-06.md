# 语音报告原子提交与状态轮询实测

## 缺陷与保留改动

旧链路正常报告每次创建新实体，报告保存和消费者写 COMPLETED 分开；同类内部事务方法也未经过事务代理。会话删除没有和报告提交共同锁定父记录。评估接口读取活动会话缓存，数据库状态完成后可能仍不可见。

保留独立 VoiceInterviewEvaluationPersistenceService：事务内重新锁定会话、按 sessionId 复用报告并同时置 COMPLETED/清错误；删除先锁同一父会话再读子记录。已完成且有报告的重放不再调用评估模型；生成期间会话已删除则返回 SKIPPED。消费者不再单独第二次写完成状态。模型和参考材料编排仍在事务外。

成功提交后调用原有精确会话缓存失效回调；评估 GET/POST 从数据库读取任务状态，避免缓存中的旧状态遮蔽完成报告。正常会话缓存及其他会话操作的语义不作为本组并发证明。

## 同场景对照与测试口径

旧实现和新实现使用同一组六项受控 H2 行为用例，Spring 真实代理、真实 JPA/数据库事务，UnifiedEvaluationService 为替身，外部模型调用为 0。仅测试配置更新了新持久化依赖；六个用例和输入保持相同：

| 场景 | 旧实现 | 新实现 |
| --- | --- | --- |
| 空报告与完成状态一起提交 | 状态未完成 | 通过 |
| 重复投递不重复插入、不再次评估 | 唯一约束失败 | 通过 |
| 生成期间删除会话，不留下报告 | 孤立报告 | 通过 |
| 完成状态数据库约束失败，报告回滚 | 没有一起写完成状态，断言失败 | 通过 |
| 模型在事务外，提交读取最新会话元数据 | 使用起始旧元数据 | 通过 |
| 轮询不被旧缓存遮蔽完成报告 | 返回旧状态 | 通过 |

旧组实际 1 suite / 6 tests，6 failed、0 errors、0 skipped；新组实际 1 suite / 6 tests，全部通过。新实现另执行 `--rerun-tasks :app:test --no-daemon`：92 suites / 461 tests，全部通过、0 skipped。普通 test 排除 integration 标签，六项 H2 是单独运行，不将两者写成同一测试任务。

## 真实 PostgreSQL / Redis / REST

旧/新隔离 App 分别执行同一请求序列：POST 创建受控语音会话 → POST 触发空评估 → 实际 Redis Stream 消费 → 等待数据库完成 → GET 轮询 → DELETE 清理。无 WebSocket、无 ASR/TTS、无对话、无外部模型评分。

旧运行 sessionId=39：数据库报告 1 条且 COMPLETED，但 GET 看不到完成报告。新运行 sessionId=40：数据库报告 1 条且 COMPLETED，GET 可见 COMPLETED 与空报告。请求输入文件字节相同。旧 App 启动于代码变更之前，保留停止前的进程/工作区/依赖/health 绑定；新 App 在完整测试构建后启动并验证。

两侧删除后各自会话/消息/报告与准确 Redis key 均为 0；公开索引和零会话范围恢复，Token 指标前后相同。本轮没有真实简历/JD、生产用户数据或模型额度消耗。

冻结运行位于 `observability/experiments/voice-frame-pipeline/runs/`：

- `business-persistence-voice-baseline-20261006-r1`
- `business-persistence-voice-candidate-20261006-r1`
- `business-persistence-voice-full-20261006-r1`
- `voice-empty-live-baseline-20261006-r1`
- `voice-empty-live-candidate-20261006-r1`

## 边界与后续

真实接口证明空评估的任务状态、报告可见性与删除链路；评分内容、麦克风/ASR/TTS、语音起播时延尚未在此验证。生成期间删除与提交回滚由受控 H2 故障证明；并未把它写成 PostgreSQL 并发压力测试。旧有 PROCESSING 加已存在报告的迁移恢复、并发状态更新、Redis 故障、完整六项异步故障矩阵继续审查。报告正常重放不等于外部模型严格仅调用一次。

不放宽出题/评分语义、正式浏览器语音、RAG 新资料检索/生成/引用/成本门。总体目标保持 active。
