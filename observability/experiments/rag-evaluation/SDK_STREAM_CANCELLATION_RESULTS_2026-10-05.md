# 模型流连接取消：冻结对照与保留决定

## 结果和实际价值

**保留 Spring AI 2.0.1 依赖升级**，配置变动仅 `gradle/libs.versions.toml`。会话停止/超时从仅结束本地订阅，补齐到释放 SDK 响应流的连接；已有部分正文、数据库状态和前端终态逻辑保留。

实际 Registry/SDK 对本地受控 HTTP SSE 服务器：旧依赖中 4 种取消/超时场景均未在 1.8 秒观察窗口内关闭连接；候选及正式配置均在该窗口内观察到对端 EOF。服务端在测量后才执行 fixture 清理，清理引发的关闭不计作通过。

这是每臂 4 种固定场景的连接正确性验收，**不是成功率、正式取消时延或成本改善数据**。原 8 次 Flash/27B 应用模型授权不重复使用。S0–S4 总目标 active。

## 方案与冻结条件

先保存 Spring AI 2.0.0 实际源码/版本目录/依赖图和受控输入，再使用独立 Gradle init 候选解析，最后改变正式版本目录并复验。模型始终从 `LlmProviderRegistry.getChatClientOrDefault` 获取；本地使用固定假密钥，无真实模型调用或外部用户数据。

Raw ServerSocket 绑定 loopback 随机端口，收到同一条固定公开 fixture 请求，发送合法 UTF-8 JSON SSE 或明确的 malformed-response。另一个 reader 直接观察客户端 EOF，避免拿 Flux 的 CANCEL 事件冒充网络关闭。每用例 finally 关闭服务器、自己的 socket 和虚拟线程；JavaExec 进程自然退出，不把测试服务的自行关闭计入结果。

| 场景 | 2.0.0 基线 | 2.0.1 候选 | 2.0.1 正式版本配置 |
| --- | --- | --- | --- |
| 收到首段后重复取消 | 未观察到连接关闭 | peer EOF | peer EOF |
| 首段前取消 | 未观察到连接关闭 | peer EOF | peer EOF |
| 响应头前取消，服务端延迟 500ms 发头 | 未观察到连接关闭 | peer EOF | peer EOF |
| 首段等待超时 | 未观察到连接关闭 | peer EOF | peer EOF |
| 正常结束 | 正文/usage 正确，连接关闭 | 同左 | 同左 |
| 故意损坏 JSON | 正确 ON_ERROR，连接关闭 | 同左 | 同左 |

三个正常结果正文均严格等于 `中文😀\\n第二段`（含字面反斜杠 n），末条 usage=3；取消后的迟到内容未交付客户端。6 场景 × 3 依赖臂共 18 次本地 HTTP 请求，实际模型调用 0。

候选核心 Spring AI 模块升至 2.0.1，两个 starter 尚为 2.0.0；因此另做正式版本配置复验，确认全部 19 个解析出的 Spring AI 模块为 2.0.1。OpenAI Java core 同时从 4.39.1 升到 4.49.0，agent-utils 保持 0.10.0。连接收益归于这组依赖更新，不能单独归因于一行 onDispose 或宣传技术栈先进。

## 回归与真实应用

- 独立候选：400 项后端全量 + 4 项 H2 事务集成通过。
- 正式配置：400 项后端全量 + 4 项 H2 事务集成通过，涵盖已有结构化输出、Registry 路径/工具参数、语音处理及 RAG 正确性。
- 前端源码与已通过构建/14 项协议和实际浏览器验收的版本一致，本次只改后端依赖目录。
- 升级后隔离 App 健康 UP，实际启动 classpath 确认 Spring AI 2.0.1 / OpenAI Java 4.49.0。
- 一次公开文档正常生成 + 一次中途取消通过：每次恰好一个 terminal；保存正文与接收正文相同，分别 COMPLETED / CANCELLED，取消未误标完成。
- 这两次真实生成已知 Chat **7,754 Token**、Embedding **108 Token**，另 1 次取消 Chat 用量未知。局部回答报告 usage=0 只表示未收到最终 usage，不当作实际零成本。
- 自己的会话删除后，15 文档/123 向量/0 RAG 会话和消息。原公开向量 SHA 保持 `76d903d460a4f34c5f3fedcc1e31b998cb999a6fb8d2ad6105760c1ed30b495b`。

终态正确性前一轮的 6 次与本轮 2 次分开记账：合计已知 Chat 31,065 / Embedding 436 Token，4 次 Chat 用量未知；不是总目标的累计账本。

## 限制及保留的失败证据

响应头前取消并非立即中止未完成 HTTP 请求：本地 delayed-header 场景仍等待响应头到达才关闭，原始值约 0.61s，不能外推生产网络。关闭客户端连接也不能证明供应商停止生成或计费。

timeout / malformed-response 的原始 closeAfterCancelMs 以终态后时间作为参考，个别负值是参考点选择的问题；摘要明确排除这些值的时延解释。本轮只采用 EOF 正确性，不报告 P50/P95。

中间故意损坏 JSON 的错误栈是预期用例。首次 XML 复制受 Windows MAX_PATH 影响，已用长路径前缀补全 XML；保留恢复说明，产品测试没有因此失败。没有为实验添加 System.exit 来隐藏资源回收；JavaExec 等 SDK 空闲线程自然退出，因此 wall time 不是模型/取消时延。

仍需完成正式 RAG 检索/生成新资料门、≥100/臂及≥3时间窗的实际浏览器语音实验、S4 基础业务与异步故障验收。本轮是有用的流资源修复，不替代必要证据/最终回答收益要求。

## 复核入口

`runs/sdk-wire-cancellation-20261005-r1` 保存基线和独立依赖候选；`r2` 保存正式目录改动前后 118 份源码/配置、全部解析依赖的版本及 JAR SHA、6 场景输入生成器、XML、应用原始事件、用量及运行时 classpath 验证。`summarize_sdk_wire.py` 从保存数据执行验收断言，`reviewed-summary.json` 是结果；封存后不修改 run 内容。

一手参考：[Spring AI 2.0.1 OpenAiChatModel](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java) 注册响应关闭回调。本轮以本机解析的实际 JAR 和 socket 对照为运行证据。

封存后独立复核：两版 SDK 共 571 个文件无差异；加上终态三版共 1,513 个文件无差异，精确密钥值扫描为 0。此行仅补记根报告，封存版本保持原样。
