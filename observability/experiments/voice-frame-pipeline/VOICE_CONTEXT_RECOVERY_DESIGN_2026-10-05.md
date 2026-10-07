# 指定岗位预加载与 ASR 独立恢复：方案和自审

## 实际失败证据

`20261005-r5/browser-preflight-r1` 的 A 组真实页面收到完整问题及 output_pcm 报告，提交→起播 6069.89ms；只有一轮，不是正式 A/B。流式请求在 Spring AI OpenAiChatModel 的工具片段合并处出现 NoSuchElementException，随后退回整段生成。无音频输入约 23 秒后 ASR SDK 超时，页面识别状态无法恢复。该轮 AI 回复仍完成，不能把 ASR 提示错误写成已经中止播报。

原始源码、报错栈、实际模型/音频、客户端报告、页面截图与 usage 均保留；B 组只启动，没有该轮模型回复样本。观察到 Chat 计数器 3291 Token；失败流式请求完整 usage 未取得，不能宣称 3291 是整批真实总消费。TTS 开场247、回复239，合计486 Token，无缺失。无麦克风识别输入，ASR连接用量未取得，不补零。

异常位置以本机2.0.0 jar字节码和栈为准。上游 [OpenAiChatModel 源码](https://raw.githubusercontent.com/spring-projects/spring-ai/v2.0.0/models/spring-ai-openai/src/main/java/org/springframework/ai/openai/OpenAiChatModel.java) 也在工具信息转换时直接取可选字段。尚未保存该失败供应商完整原始工具 SSE，因此不能断言是某个特定缺字段或所有模型都会失败。

## 候选一：已选定 Skill 的确定性上下文

复用 InterviewSkillService 启动时读取的预设 SkillDTO.persona；为已指定岗位直接加入完整角色/规则与既有语音约束、简历清洗和防注入指令。使用无工具 ChatClient 流式回答，消除重复加载同一岗位的工具往返。模板放在 resources/prompts/voice-skill-preloaded.st。

开关 skill-preload-enabled 默认 false；只有预设岗位参与候选。空岗位/custom仍保留既有分支；不存在或空规则明确失败，不以空 persona 静默切换无工具。未加载参考正文时不能声称已查阅参考资料。没有升级框架、修改模型权重或伪造工具结果。

这是一条针对已知岗位的产品编排候选，不能宣称修复所有 Spring AI 流式工具调用。需要验证10个岗位的规则一致性、项目追问与约束质量；少量成功仅允许继续采集。后续帧流 A/B 若共用预加载，必须单独披露，不能将它的收益算作帧流贡献。

## 候选二：ASR 生命周期与独立恢复

ASR错误用 asr_unavailable 作用于识别状态，保留当前回复；提供重新连接语音按钮，显式请求 reconnect_asr。已 ready 时只确认；未 ready 时在既有执行器重连，继续既有最多3次建连准备检查。初始连接/重连耗时与供应商失败另记，不持续建立空音频连接。

识别器回调按当前实例过滤；旧错误/结束/识别片段不能覆写新实例状态，同一实际错误只通知一次。主动stop先移除实例，迟到SDK回调被丢弃；当前实例意外结束则明确识别不可用。不能把客户端连接确认当成实际语音识别通过。

## 评审与验收

同一 Agent 自审，没有独立人工或第二位Agent批准。结论：可实现并进入新的预检目录；预加载默认关闭，前一轮原始数据不重写。

必选门：完整后端fresh回归、前端生产构建；真实页面经历识别超时后点击重连恢复ready，并完成下一轮；实际流式回答必须保持句级输出、文字/合成终稿一致、有序有效音频、可取得usage或明确缺失。实际浏览器P95/间隙/取消正式门仍执行根目标，不用此预检代替。
