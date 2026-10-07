# 结构化输出：统一重试预算并补上严格字段校验

## 实际问题

上一阶段真实诊断出现格式校验递归，不能把 StructuredOutputInvoker 的两次尝试当成仅两次模型请求。继续调用真实模型前，使用实际 Spring AI 2.0.1 ChatClient 链与离线 ChatModel 验证；不访问模型接口。

## 同一回归对照

有意义的旧版对照为 structured-retry-baseline-20261005-r3：四项中三项失败。

- 持续返回非法 JSON，配置 maxAttempts=2，实际执行 ChatModel.call **8 次**。
- 返回合法 JSON `{}` 但缺少必填 value，SDK 校验耗尽后仍返回最后结果，转换可接受，未明确失败。
- 第一次非法、第二次有效时，SDK 内部修复未计入中央失败尝试，中央指标与实际尝试不一致。
- 显式关闭 Schema 的原转换路径原本通过。

候选统一在中央调用器本地执行与 SDK 一致的 DRAFT_2020_12 Schema 校验，严格拒绝多余尾随正文；不再调用 SDK 的递归 validateSchema()。本地 JSON/字段失败都进入原有中央重试，仍保留关闭 Schema 时的既有转换/本地引号修复路径。JSON Schema Validator 3.0.1 已由 Spring AI 引入，本次显式声明直接依赖，没有更换该库版本。

同一四项全部通过：持续无效结果仅执行 **2 次** ChatModel.call；缺字段不能成功；一次失败一次成功的中央指标为各一次；关闭 Schema 的原路径仍一次成功。新鲜全量 **85 组 / 427 项，零失败/错误/跳过**。

这是解析/字段校验与重试的真实框架对照，不能写成生产性能提升、模型语义准确率或所有 HTTP 请求都最多两次；ToolCallingAdvisor 内部继续调用和底层网络重试仍可能增加调用数。不能因严格 Schema 通过就相信评分内容。

## 失败与采集器修复保留

- baseline r1 为测试编译失败：SimpleMeterRegistry 不实现 AutoCloseable。未执行测试或模型。
- baseline r2 为 mock 未提供 ChatModel.getOptions()，四项失败且 fake 模型调用为零，不作旧重试行为证据。
- 修复测试装配后 r3 才得到上述 8 次/字段漏校验行为；源码与 XML 均保留。
- 评估诊断采集器已改为使用实际 interviewEvaluationExecutor，避免同步执行器造成的等待预算失效。新版采集器仅完成编译复核，尚未重新调用真实模型或证明端到端预算。

## 评审与后续

执行代理审查：保持模型获取途径、默认模型、中央失败映射与 Schema 开关；完整 Schema 不满足时必须失败，不能靠反序列化成功放行。下一步审查评估是否需要工具调用以及预算到期后的重试行为，再做小批量真实复验。语义质量、能力标识、训练有效性与原 S0–S4 门继续 HOLD/active。

本次无新增真实模型用量。真实模型诊断的 89,754 已暴露 Token 仍计入上一阶段，不重复相加。官方背景：[Spring AI 自校验说明](https://docs.spring.io/spring-ai/reference/api/structured-output/validation.html)。

## 最终复核与恢复

重试四版共 123 个文件封存；加前述训练接线三版 197 与来源绑定五版 362，合计 **十二版 / 682 个文件**，独立 SHA-256 全量复核零差异、精确密钥值扫描零命中。新采集器编译返回 0，无新增真实调用。

默认 App 已重新编译启动，exec handle 35803，现场 PID 45148，127.0.0.1:18080/health UP，Gradle manifest 归属本 workspace；Spring AI 2.0.1 / JSON Schema Validator 3.0.1。默认 Flash、ASR 3.0/2000ms、原 TTS 配置保持，无诊断或跨模型覆盖。自己的会话为零，原公开向量指纹不变。后续操作须重新核对监听 PID/主类/manifest，不能只用历史 PID。
