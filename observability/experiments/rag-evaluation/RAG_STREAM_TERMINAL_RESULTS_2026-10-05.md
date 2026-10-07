# RAG 流式答案终态与取消验收

## 保留决定

保留会话式 RAG 的终态正确性改进：正常完成、失败和用户取消分别持久化；已经收到的正文不丢失，失败/取消回答不进入后续成功对话上下文。前端只在收到明确 terminal 后标记完成，支持停止、错误/中断提示、历史回放及切换会话时取消旧请求。

本轮不是正式检索、生成质量、响应时延或成本 A/B。Spring AI 2.0.0 的供应商连接关闭仍未通过验收；不能从本地 dispose 推断远端停止生成或计费。总目标 S0–S4 保持 active。

## 先验证原缺陷

冻结旧实现 101 个文件后，两个实际回归都失败：中途模型异常后错误地保存 completed=true；取消后部分正文未保存。失败测试源码、XML 和执行日志保留在 r1/baseline-proof 和 baseline-regression.log。

候选设计及自评审在 RAG_STREAM_TERMINAL_DESIGN_2026-10-05.md。Controller 仅委托；外部调用不进入数据库事务；会话行锁管理消息序号，消息行锁使 terminal 幂等；保存成功先于 terminal。删除会话后不重新创建消息。

## 已验证范围

| 验收层 | 保存的结果 | 证据边界 |
| --- | --- | --- |
| 后端全量 | r1 399、r2 399、最终 r3 400 项通过 | 最新新增无工具客户端真实受控 HTTP 测试；中间 compile 失败亦保留 |
| 流编排 | 14 项通过 | 正常、部分失败、显式/断连取消、准备中取消、超时、空流、保存失败、大小上限、重复订阅、同会话并发、完成取消竞态 |
| H2 事务集成 | 4 项通过 | 取消不被迟到完成覆盖、删除不复活、并发序号、事务回滚 |
| 前端构建 | pnpm run build 通过 | 没有据此宣称实际浏览器通过 |
| 实际 TypeScript API/解析器 | 14 个受控用例通过 | UTF-8 单字节、CRLF、JSON 多行、字面反斜杠、部分失败、取消、无终态 EOF、reader 释放；模型调用 0 |
| HTTP + 真实模型 | r1 首次失败被正确保存 FAILED；r2/r3 正常和取消均通过 | 正文与数据库一致、每次恰好一个 terminal、自己的会话全部删除 |
| 实际浏览器 + 受控接口 | 部分失败、停止、刷新回放、切换会话、缺失 terminal 中断均验证 | 受控故障，模型调用 0；截图、DOM、接口事件保留 |
| 实际浏览器 + 真实模型 | 一次正常问答及刷新回放通过，COMPLETED、8 条来源预览 | 单次功能验证，不能外推成功率/质量；初始 CORS 拦截也保留 |

新协议为 `start/delta/heartbeat/terminal` 命名 JSON SSE，作用于 `/api/rag-chat/sessions/{sessionId}/messages/stream`。停止路由检查 session/message 归属。独立的旧 `/api/knowledgebase/query/stream` 仍不是这套 terminal 协议，不把此次结果泛化到所有流式接口。

## 现场失败与处理

1. r1 真实流在 Spring AI 工具片段合并 Optional.get 处失败，正确返回 FAILED。改用 Registry 的无工具客户端并显式 toolChoice=none 后，r2/r3 应用回归正常。尚无供应商原始片段，不能把某个官方 issue 的具体空 ID 成因说成已确认根因。
2. r2 首次编译误用了已 build 的 options，当前 API 要求 Builder；修正后全量回归通过，原失败日志保留。
3. 前端检查首次 esbuild 加载路径不正确；中间测试把已消费 JSON body 的锁状态误判为泄漏。最终检查对真正流式 reader 的取消和释放断言，14 项通过；保留失败记录，不把修测试当产品收益。
4. 第一版浏览器 fixture 的 log 形参 event 冲突；修正后的 fixture 用新版本执行，旧失败诊断保留。控制台亦有初始 HistoryPage 伪接口错误，不能宣称零错误。
5. 真实浏览器初始 CORS 被拒，未调用模型；运行器为受控页面临时指定唯一 localhost 来源，回归后已恢复默认来源。重启时一次日志占用失败发生在 Gradle 启动前，未发模型请求。

## 实际模型用量与数据清理

- 共 6 次真实 RAG 生成请求：r1 失败 1 次，r2 正常/取消 2 次，r3 正常/取消 2 次，真实浏览器正常 1 次。
- 已观测 Chat **23,311 Token**、Embedding **328 Token**；另 **3 次 Chat 用量未知**（一次失败、两次取消），未知不是 0。
- 先前授权的 8 次应用 Flash/27B 对照已用完并独立封存，本轮没有重复使用该授权范围。
- r2/r3 正常请求输入均为 7,708 Token，无工具路径未证明输入 Token 减少，不写成本降低。
- 原公开向量 SHA-256 保持 `76d903d460a4f34c5f3fedcc1e31b998cb999a6fb8d2ad6105760c1ed30b495b`；自己的受控会话清理后，15 文档、123 向量、0 RAG 会话/消息。临时页面、Vite 和 fixture 已关闭；隔离 App 恢复默认来源且健康 UP。

## 尚未通过的门与下一步

当前实际 Spring AI 2.0.0 JAR 未将 FluxSink 取消绑定 AsyncStreamResponse.close。官方 [2.0.1 实现](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java) 提供 onDispose close；先以本地 SSE 服务器、同输入、冻结依赖做连接级 A/B，再决定是否升级，详见 SDK_STREAM_CANCELLATION_REVIEW_2026-10-05.md。

本轮没有处理进程崩溃后 GENERATING 占位消息的恢复；旧消息新状态为 null，历史误标记不做猜测性批量修正。正式 RAG 新资料质量门、必要证据/延迟门、语音浏览器正式规模及 S4 业务/异步故障门仍需完成。

## 可复核入口

三版目录 `runs/rag-stream-terminal-20261005-r1`、`r2`、`r3` 分别保留 baseline/candidate source、manifest、测试 XML、原始请求/事件、真实 usage、失败日志和浏览器截图。summary-reviewed.json 与 r3/aggregate-reviewed.json 由 summarize_rag_terminal.py 从保存证据生成。每版封存后以 artifacts.sha256.json 独立复核，不修改封存内容。

## 封存后的后续进展

三版封存共 942 个文件，独立 SHA 复核无差异。随后完成 SDK 本地 socket A/B、正式版本配置和升级后实际应用正常/取消回归，保留 Spring AI 2.0.1；详见 `SDK_STREAM_CANCELLATION_RESULTS_2026-10-05.md`。该后续进展不改写封存时的“连接尚未验收”历史记录，也不补算为本轮的正式质量或成本收益。
