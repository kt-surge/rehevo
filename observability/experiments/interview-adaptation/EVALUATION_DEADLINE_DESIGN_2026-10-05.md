# 评估截止与取消方案

## 已复现的问题

当前批次使用 CompletableFuture.supplyAsync；cancel(true) 取消等待状态，但没有向实际模型工作线程传递中断。三个真实 Spring AI ChatClient 加离线阻塞模型的边界测试全部失败：调用前已中断仍发请求、一次失败中断后仍重试、报告超时返回而批次线程仍工作。原始源码、失败 XML 与日志冻结在 evaluation-deadline-baseline-20261005-r1。

失败题重试与二次汇总直接在调用线程执行，只在请求前检查剩余预算，单次请求可越过整份报告截止时间。

## 方案与评审

复用现有有界 interviewEvaluationExecutor，使用 FutureTask 提交批次，超时与等待中断时 cancel(true) 实际工作线程；失败题重试和汇总同样在剩余报告预算内等待。迟到结果不进入返回报告，既有评分来源校验与兜底保持。

中央 StructuredOutputInvoker 增加兼容原签名的预算条件重载，每次请求前及返回后检查线程中断和调用方截止条件。底层清除中断标记时，截止条件仍阻止新一次结构重试。记录一次实际尝试对应的成功/失败，整次调用只记录一个终态。

只复用现有执行器和 Java FutureTask，不新增线程池或依赖。不把工具访问、模型、提示词、题号规则修改混入该离线对照。等工具访问真实诊断终止、证据冻结后再实施。

## 验收与限制

- 三个原始回归案例必须由失败转为通过；补测超时发生在汇总/失败题重试、底层清除中断后预算仍能阻止重试。
- 不接受截止后开始新的模型尝试，报告只采用截止内完成的有效结果；线程取消与终态指标不得重复记录。
- 运行完整后端回归。真实 SDK 的连接释放须另行用 loopback HTTP 测量；离线中断测试不证明供应商停止计算或计费。
- 正式产品质量、语音体验及 RAG 门保持原标准；这项修复解决取消和资源占用，不产生模型准确率指标。

## 实际 HTTP 负结果后修订

FutureTask 与中央预算检查使六个离线场景通过，但真实 Registry + Spring AI 2.0.1 / SDK 4.49.0 的两个同步 HTTP 案例（响应头前、响应头后）均未在取消后 1.8 秒观察窗内释放连接。保留 evaluation-deadline-candidate-20261005-r2 的失败 XML、源码与日志，不能以线程中断替代连接释放。

给显式带预算的评估调用使用 stream().content() 聚合完整文本后做既有 Schema/DTO 校验；原无截止的八参数调用保留同步传输。聚合订阅在工作线程中断时取消，上游连接释放必须由同一实际 Registry loopback 测试证明；未收到完整输出时不得评分。项目已有流式依赖和 SDK 取消实现，无新增依赖或模型更换。

技术核对：[Spring AI ChatClient 官方文档](https://docs.spring.io/spring-ai/reference/api/chatclient.html) 提供聚合流内容再结构转换的方式；本地 reactor-core 3.8.6 BlockingSingleSubscriber 字节码核对中断后 dispose()/Subscription.cancel()，实际网络行为仍以本地 HTTP 实测为准。真实 Flash 需小批量验证完整 JSON、用量与失败，不从离线网络样本估计生产效果。

## 最终候选：任务范围内直接取消 HTTP Call

流式聚合也未通过服务端持续静默场景：r4 全量 435 项执行，433 通过、两个实际 HTTP 连接释放失败；不采用这项传输变更。r3 是夹具泛型编译失败、实际执行零项，误复制的前次 XML 单独标为 previous-task-results-not-executed，不计当次结果。

新候选继续同步传输，不改变模型请求协议。AiCallCancellation 只绑定一个评估任务的同步线程；ApiPathResolver 使用现有 SpringAiOpenAiHttpClient.Builder.interceptor seam 注册该线程的实际 Call.cancel()，FutureTask 取消时调用它。回调持续到整个任务结束，以覆盖响应头返回后的正文读取。没有新线程、新依赖、内部请求头或共享客户端整体取消。任务结束移除绑定，取消未开始的任务不创建 HTTP 请求。

[Spring AI HTTP 客户端官方 API](https://docs.spring.io/spring-ai/docs/2.0.x/api/org/springframework/ai/openai/http/okhttp/SpringAiOpenAiHttpClient.html) 与本地 2.0.1 字节码核对同步 Call.execute、Builder.interceptor；[SDK 4.49.0 AsyncStreamResponse 源码](https://github.com/openai/openai-java/blob/v4.49.0/openai-java-core/src/main/kotlin/com/openai/core/http/AsyncStreamResponse.kt) 关闭回调不代表响应头前即时取消。本次仅实现同步评估任务取消，不扩展为任意异步流的通用取消方案。复测两种本地 HTTP 静默场景、任务隔离和全量后端，仍不宣称供应商停止计费。

自动审批拒绝了整文件快照回写（可能覆盖未提交改动）；核对当前与本轮 r1 的完整差异，仅为本轮已失败的流式实验后，采用精确补丁撤回这些行，保留中央 Schema、重试和截止校验。
