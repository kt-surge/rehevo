# Rehevo 指标契约（0.2）

本文件定义业务指标的名称、单位、成功/失败口径和允许标签。实现入口为
`common/metrics/AppMetricNames` 与 `ApplicationMetrics`；框架提供的 Spring AI
Observation、JVM、HTTP、数据库连接池指标保持由 Actuator/Micrometer 自动暴露。

## 标签红线

只允许 `status`、`stream`、`path`、`interaction`、`streaming`、`stage`、`mode`、`reason` 八类有限标签。
`sessionId`、`turnId`、`userId`、`knowledgeBaseId`、问题、Prompt、文档名、原文和错误详情只可进入日志或 Trace。

## 语音

| Prometheus 指标 | 类型/单位 | 标签 | 口径 |
| --- | --- | --- | --- |
| `app_voice_interview_active_sessions` | Gauge/会话数 | 无 | 当前 WebSocket 会话数 |
| `app_voice_interview_asr_ready_total` | Counter/次 | `status` | ASR ready 回调成功 |
| `app_voice_interview_asr_reconnect_total` | Counter/次 | `status` | ASR 重连尝试 |
| `app_voice_interview_asr_dropped_audio_total` | Counter/帧 | `status` | AI 播放冷却或 ASR 未 ready 时丢弃的音频帧 |
| `app_voice_interview_llm_first_token_latency_seconds` | Timer/秒 | `status` | 从提交回答到首个 LLM Token |
| `app_voice_interview_first_audio_latency_seconds` | Timer/秒 | `status` | 从提交回答到向前端发送首个音频帧 |
| `app_voice_interview_tts_duration_seconds` | Timer/秒 | `status` | 一次 TTS 合成耗时 |
| `app_voice_interview_turn_duration_seconds` | Timer/秒 | `status` | LLM + TTS 整轮耗时 |
| `app_voice_interview_turn_cancelled_total` | Counter/次 | `status`,`reason` | 已进入处理中的 Turn 被取消；`reason=user` 为用户主动停止，`reason=disconnect` 为连接断开 |
| `app_voice_interview_turn_stale_dropped_total` | Counter/次 | `status` | 取消或新 Turn 后，被状态机拦截且未发送的 LLM/TTS 迟到回调 |
| `app_voice_interview_turn_cancel_ack_latency_seconds` | Timer/秒 | `status` | 服务端收到取消到写入取消确认事件的耗时；不等同于浏览器实际收到确认的耗时 |
| `app_voice_interview_turn_client_cancel_ack_latency_seconds` | Timer/秒 | `status` | 浏览器收到带匹配请求号的取消确认后上报的本地往返耗时；活跃 Turn 和生成完成但客户端仍播放的状态均适用。仅为客户端观测，不能作为服务端 SLA 或安全审计口径 |
| `app_voice_interview_client_first_audio_received_latency_seconds` | Timer/秒 | `status`,`mode` | 同一浏览器单调时钟，从提交到首次可接受音频到达；开场及缺失请求号不入样本 |
| `app_voice_interview_client_playback_start_latency_seconds` | Timer/秒 | `status`,`mode` | 同一浏览器从提交到播放启动标记；`output_pcm` 为输出流位置跨过起播点后，以 `getOutputTimestamp()` 映射到页面单调时钟的浏览器输出估计；`scheduled_pcm` 为音频时间线跨过起点后的观察上界（历史报告曾为成功调用 `start(0)`，新旧口径不得合并比较）；`html_playing` 为媒体 `playing` 事件。三种模式分别统计，均不是麦克风或扬声器物理出声实测 |
| `app_voice_interview_client_playback_reports_total` | Counter/次 | `status` | 已接受报告 `success` 或无效/重复报告 `discarded`；不是已提交轮次数，也不用于计算采样覆盖率 |

客户端报告需匹配本会话当前轮次及服务端回显的提交请求号，首音频发送后最多接收一次；已取消、失败、旧轮次、重复、非有限或乱序时间均丢弃。接收的毫秒值保留小数，并限定 `0 ≤ received ≤ playback ≤ 120000`。ID 只用于协议关联与日志，不进入 Prometheus 标签。

取消确认分两种：活动轮次的 `turn_cancelled` 和已完成/空闲状态的 `cancel_confirmed`。后者仅确认本服务收到请求，不改变已完成终态，不增加活动轮次取消数，也不证明供应商远端停止或扬声器停声。两个确认 Timer 包含这两种服务端状态，实验需保留逐事件类型；旧版只覆盖活动轮次的计时不能直接混算。旧轮次确认不得清理新轮次播放。

这些数值来自客户端自报，可用于体验诊断，不是服务端 SLA。看板中的采样数与分位数必须同时查看；少量样本不作为稳定 P95，缺失、取消、失败及旧客户端单独计入实验原始记录。覆盖率分母应从同一实验的实际提交记录建立，不能直接用报告计数、所有 Turn 或含开场题的服务端完成计数替代。对照实验保留逐轮毫秒值、客户端与配置指纹，直方图不能替代配对原始数据。

## Redis Stream

| Prometheus 指标 | 类型/单位 | 标签 | 口径 |
| --- | --- | --- | --- |
| `app_async_stream_enqueued_total` | Counter/条 | `stream`,`status` | 入队成功或失败 |
| `app_async_stream_task_total` | Counter/处理尝试 | `stream`,`status` | 完成、失败、跳过、重试或恢复完成；`retry` 仅在实际 XADD 成功后计数，入队失败且 FAILED 已持久化计为 `failure`；终态也写入失败时保留 Pending，不计为已重试或已终止 |
| `app_async_stream_processing_seconds` | Timer/秒 | `stream`,`status` | 单条任务处理耗时 |
| `app_async_stream_backlog` | Gauge/条 | `stream` | Redis consumer group lag |
| `app_async_stream_pending` | Gauge/条 | `stream` | Redis consumer group Pending 数 |
| `app_async_stream_oldest_pending_idle` | Gauge/毫秒 | `stream` | 最久 Pending 消息的未确认 idle 时长 |

## RAG

| Prometheus 指标 | 类型/单位 | 标签 | 口径 |
| --- | --- | --- | --- |
| `app_rag_vectorization_total` / `app_rag_vectorization_seconds` | Counter、Timer | `status` | 文本切块、Embedding、写入和提升成功/失败 |
| `app_rag_vectorization_chunks` | Summary/Chunk 数 | `status` | 成功任务的切块数量 |
| `app_rag_retrieval_total` / `app_rag_retrieval_seconds` | Counter、Timer | `status`,`path` | 主检索或兜底检索耗时及结果 |
| `app_rag_retrieval_hits` | Summary/文档数 | `path` | 每次成功检索命中数 |
| `app_rag_retrieval_no_hit_total` | Counter/次 | `path` | 成功检索但无命中 |
| `app_rag_retrieval_fallback_total` | Counter/次 | `status` | 兜底检索被执行并成功或失败 |
| `app_rag_retrieval_stage_total` / `app_rag_retrieval_stage_seconds` | Counter、Timer | `stage`,`status` | 分阶段候选检索耗时；`stage` 仅允许 `vector`、`lexical`、`fusion`、`context_expansion`、`rerank` |
| `app_rag_retrieval_stage_candidates` | Summary/文档数 | `stage` | 对应阶段输出候选数；相邻补全以最终保留的上下文段数计数 |
| `app_rag_query_rewrite_total` | Counter/次 | `status` | 改写成功、失败或跳过 |
| `app_rag_answer_total` / `app_rag_answer_seconds` | Counter、Timer | `status`,`interaction` | 同步或 SSE 回答耗时、失败或无模型调用跳过 |
| `app_rag_evidence_gate_total` | Counter/次 | `status` | 证据充分性门的结果：`success` 为通过，`failure` 为观察到证据不足，`skipped` 为关闭观察门 |
| `app_rag_routing_total` | Counter/次 | `status` | 结构化路由观察建议：`retrieve`、`clarify`、`abstain`；默认不改变实际检索或回答路径 |

Dashboard 和告警阈值属于 0.3；本契约只固定数据口径与标签边界。

## 面试评估

| Prometheus 指标 | 类型/单位 | 标签 | 口径 |
| --- | --- | --- | --- |
| `app_interview_evaluation_reports_total` | Counter/份 | `status` | `complete` 表示所有已回答题均评分成功；`partial` 表示存在评分失败；`empty` 表示没有有效回答 |
| `app_interview_evaluation_questions_answered_total` | Counter/题 | 无 | 用户提交了非空回答的题数 |
| `app_interview_evaluation_questions_scored_total` | Counter/题 | 无 | 得到有效结构化评分的题数 |
| `app_interview_evaluation_questions_failed_total` | Counter/题 | 无 | 有回答但模型评分或结构化输出失败的题数 |
| `app_interview_evaluation_questions_evidence_supported_total` | Counter/题 | 无 | 成功评分且至少保留一条可在回答原文逐字定位的证据 |
| `app_interview_evaluation_coverage` | Summary/0—1 | `status` | 成功评分题数 ÷ 已回答题数；不等同于答题完成率 |
| `app_interview_evaluation_evidence_coverage` | Summary/0—1 | `status` | 有原文证据的成功评分题数 ÷ 成功评分题数 |
| `app_interview_evaluation_item_retry_total` | Counter/题 | `status` | 批次缺失题进行单题有限重试后的 `recovered` 或 `failure` 结果 |
| `app_interview_evaluation_batch_total` / `app_interview_evaluation_batch_seconds` | Counter、Timer | `status` | 批次评分完成、失败、被预算跳过或执行器拒绝；不包含二次汇总，`failure` 只表示该批题目会进入评估失败或重试，不代表整份报告失败 |
| `app_interview_evaluation_training_tasks_raw_total` | Counter/任务数 | `source=model_summary` | 二次汇总模型中通过字段与题号校验、进入归一化前的训练任务数；不携带能力点、题号或会话标签 |
| `app_interview_evaluation_training_tasks_normalized_total` | Counter/任务数 | `source=model_summary` | 同能力点合并并限制为最多 5 项后的训练任务数；与 raw 一起观察模型输出结构稳定性 |
| `app_interview_evaluation_training_tasks_duplicates_collapsed_total` | Counter/任务数 | `source=model_summary` | `raw - normalized` 的非负值；高于 0 表示服务端修正了模型重复能力点，不代表候选人能力提升 |
| `app_interview_live_follow_up_total` | Counter/题 | `status` | LIVE 追问观察建议：`advance`、`deepen` 或 `not_applicable`；默认仅观察，不代表题目已被跳过 |
| `app_interview_live_follow_up_relevance` | Summary/0—1 | 无 | 有相邻预生成追问时，其能力点与父题能力点一致的比例；不是候选人回答质量 |
| `app_interview_live_follow_up_duplicate_total` | Counter/题 | `status` | 相邻追问与父题前既有题目文本重复时记录；当前 `failure` 表示检测到重复，非系统错误 |
| `app_interview_plan_total` | Counter/份 | `status` | 新建会话时生成的 PREP 计划；`complete` 表示每道主问题均带能力点，`partial` 表示存在回退分类 |
| `app_interview_plan_competency_coverage` | Summary/0—1 | `status` | 主问题携带显式能力点的比例 |
| `app_interview_plan_competencies` | Summary/能力点数 | `status` | 每场计划覆盖的不同能力点数量 |
| `app_interview_plan_history_focus_competencies` | Summary/能力点数 | `status` | 本场作为“优先复测”输入的去重历史能力点数（最多 3）；作为弱项复测覆盖率的分母 |
| `app_interview_plan_retest_coverage` | Summary/0—1 | `status` | `prioritizedCompetencies ÷ requestedFocusCompetencies`；无历史重点时不写入该指标，避免把“不适用”伪装为 100% |
| `app_interview_plan_prioritized_competencies` | Summary/能力点数 | `status` | 与实际题目能力点精确匹配、因历史训练任务被提升优先级的能力点数；为 0 可能表示无历史重点，也可能表示本场未复测，需与分母指标一起解释 |

弱项复测覆盖率只在存在历史训练重点的会话上计算：

`sum(app_interview_plan_prioritized_competencies) / sum(app_interview_plan_history_focus_competencies)`

它衡量“作为输入请求复测的能力点中，有多少在实际题单里得到精确匹配”，不是候选人能力提升率；能力提升需另用同一能力点的前后评估结果计算。
