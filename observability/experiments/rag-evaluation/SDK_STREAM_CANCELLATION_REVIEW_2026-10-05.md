# Spring AI 流取消资源评审与下一步验收

## 当前证据和范围

- 项目仍使用 Spring AI 2.0.0。对本机实际依赖 JAR 的 `OpenAiChatModel` 做 javap 检查，异步流建立后只有 subscribe / completion future，没有将 FluxSink 取消绑定到 AsyncStreamResponse.close。
- 本轮已验证本地订阅、会话注册表和前端 reader 的取消/释放；**不能因此宣称供应商 HTTP 连接已经立即关闭**。供应商是否停止计费也不能由客户端断连推断。
- Spring AI 官方 2.0.1 的实现，在 createStreaming 后增加 `sink.onDispose(response::close)`。这是可验证的候选修复，不直接替代本项目的运行证据。

## 技术方案和自评审

优先在独立依赖实验中对齐 2.0.1 的补丁，避免复制整个 OpenAiChatModel 或用反射操作其私有客户端。许可证仍为 Apache 2.0；同时复核相关模块解析版本、OpenAI Java SDK、agent-utils 0.10.0 的兼容性。先做本地受控 HTTP 服务器实验，无模型调用或真实密钥。

1. 冻结 2.0.0 源码、配置、版本目录、依赖图和受控 SSE 输入。
2. 服务器持续发合法 JSON chunk；客户端收到第一段后 dispose。记录服务器观察到的连接关闭、其后写入失败/结束时刻，以及客户端 terminal、注册表和线程资源。
3. 除中途取消，还验证首块前取消、生成截止时间、正常完成、模型错误、迟到响应和重复取消；正常流的正文与 usage 不能被截断或重复累计。
4. 同一组输入在补丁依赖上复测，确认真实连接关闭，运行全部后端测试、相关 JPA 集成测试、前端构建；语音 ToolCallAdvisor 与结构化输出路径也须回归。
5. 通过后再更新主依赖，做小批量公开应用正常/取消回归，保留真实 usage 与取消用量未知记录。失败则保留 2.0.0 与本轮终态正确性改进，明确资源验收仍未完成。

这是 Agent 自评审。尚未完成供应商连接 A/B；不能把源码中的一行修复写成取消时延或生产资源指标。既定 S0–S4 和语音/RAG正式门不变。

## 一手来源

- [Spring AI 2.0.1 OpenAiChatModel](https://github.com/spring-projects/spring-ai/blob/v2.0.1/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java)：异步流建立后注册 onDispose close。
- [Spring AI 工具片段合并问题](https://github.com/spring-projects/spring-ai/issues/6591)：相容接口的空工具 ID 可影响片段合并。当前现场异常在 ToolCall Optional.get 处；未抓取供应商原始请求/响应，因此不把该 issue 的具体成因当成本次已经确认的根因。
- [阿里云 Function Calling](https://help.aliyun.com/en/model-studio/qwen-function-calling)：none 明确禁止工具选择。RAG 另使用 Registry 的无工具客户端，实际请求不携带无关 Skill 工具定义；不是依靠提示词要求模型自行不调用。
