# Rehevo 同类产品深度调研与优化取舍

> 调研日期：2026-09-27。范围：公开产品文档、开源仓库、用户论坛与 Rehevo 当前工作区。本文是产品与工程决策依据，不是竞品实测报告，也不把厂商宣传数字当作经独立验证的效果。

## 一、结论

Rehevo 已拥有文字/语音面试、简历与 JD 出题、结构化评分、RAG 和跨场训练任务，但这些功能单独看已不是稀缺能力。市场上成熟产品已经把岗位目标、场景、练习记录和报告组织成连续路径；开源项目也已经覆盖实时语音、动态追问、题库、代码题与本地模型。

最值得建立的主线是：**面向技术岗的“项目证据答辩与跨场复测”**。让用户选择一个岗位和自己的一段真实项目经历，系统依据简历、项目文档或经用户授权选取的代码证据追问技术决策；报告指出回答中有证据的内容、缺失的解释和不确定项；下一轮用同一能力、不同题面复测。产品结果应由真人盲评和跨场证据支持，而不是由模型自评总分证明。

这是一项定位建议，不声称市场上无人做过“项目证据驱动面试”。[Code Defense](https://github.com/AsadullahSamo/Code-Defense) 已展示仓库驱动出题，[FEMentor](https://github.com/Jye10032/fementor) 已在中文前端岗提供项目经历库与证据增强评分。可形成差异的是：聚焦一类技术岗后，把项目证据、评估可信度、连续训练和可靠语音体验做成经真人验证的组合。**“用了 RAG/Agent/语音”本身不是差异。**

## 二、调研方法与证据边界

- 产品能力以厂商官方帮助文档或产品页为准；它只能说明厂商公开描述了什么，不能证明实际效果。
- 开源能力以仓库 README、LICENSE、SECURITY 和 GitHub 元数据为准；未下载运行竞品，也未审计其安全性。
- Reddit/Hacker News 仅用于发现问题假设。少数帖子票数低，含开发者自荐，不能代表市场总体。
- Rehevo 以本地源码、`TODO.md`、`TECHNICAL_EXECUTION_PLAN.md` 和 `DELIVERY_EVIDENCE.md` 为准。工作区有大量未提交改动；代码存在、受控测试和真实用户收益分开表述。本次未启动服务或重新运行测试。

## 三、市场地图：哪些已成为基本配置

| 对象 | 已公开的做法 | 对 Rehevo 值得学习的点 | 不能直接照搬的原因 |
| --- | --- | --- | --- |
| [Yoodli](https://support.yoodli.ai/en/articles/9550465-practice-with-yoodli) | 按角色、公司和面试官风格创建练习，支持动态追问；[自定义 Rubric 与持续反馈](https://yoodli.ai/platform/ai-feedback) | 把“场景、预期行为、评分标准、下一步练习”放在同一训练单元；让用户知道评分依据 | 偏沟通训练与企业 Roleplay，技术正确性和项目事实仍需另做证据层 |
| [FinalRound AI](https://docs.finalroundai.com/docs/mock-interviews/starting-a-mock) | 以具体求职目标为容器，复用简历、JD、材料和历史重点；练习可选择 coding、system design、behavioral 等场景 | 岗位目标工作区、场景预设、开始前检查、报告后一键继续练习 | 其真实面试 Copilot 业务与 Rehevo 的练习定位不同；不应顺带扩成求职全平台 |
| [牛客求职侧 AI 模拟面试](https://www.nowcoder.com/interview/ai/index) | 提供项目介绍、自我介绍、薪资谈判、国央企题型和软件/通信等专项练习 | 中文岗位题型与用户熟悉的“项目深挖”入口，说明只做泛用技术问答不够 | [企业侧 AI 面试](https://hr-pre.nowcoder.com/product/interview/)是招聘筛选产品，效率数字和评分用途不能迁移为求职训练效果 |
| [FEMentor](https://github.com/Jye10032/fementor) | 面向中文前端岗，把简历、JD、项目经历库、面经题源、追问和证据增强评分串联 | 深耕单一岗位族；项目经历不只是出题 Prompt，还进入评分证据 | 公开仓库未识别到许可证，不能直接复制代码；README 的功能状态仍需实测 |
| [AI 面试准备助手](https://github.com/wenbo123-png/ai_interview_assistant) | 中文开源项目已覆盖简历/JD、RAG、出题、追问与评分；MIT | 证明“多功能 AI 面试平台”在 GitHub 上门槛已很低 | README 的随机触发追问与总评不足以证明真实面试质量；不宜照搬其宽而浅的功能组合 |
| [interviewing.io](https://interviewing.io/) | 以编码、系统设计等技术面试和资深工程师反馈为核心，同时提供 AI Interviewer | 技术岗需要贴近真实任务与有经验者的评价；可用真人反馈校准 AI 报告 | 人工服务平台的供给方式和信任基础无法靠增加模型调用复制 |
| [DeepInterview](https://github.com/ngoanpv/DeepInterview) | 公开 PREP→实时语音→评分→薄弱项教练路径，提供本地模型路线；Apache-2.0 | 面试前做重规划，实时环节保持轻量；开放可运行的替身路径便于复现 | LiveKit、LangGraph、Next.js 等整体迁移到 Java 栈成本高；其 README 也区分无密钥替身链路与需要 Provider 的真实语音链路 |
| [AI Interview Simulator](https://github.com/ZhenyuanPAN822/ai-interview-simulator) | 依据每题能力评分决定追问、覆盖不足能力或调整难度；将下一步决策写成可测试策略；MIT | 把“为什么追问/换题”做成可解释、可回归的策略，适合借鉴设计 | 当前文字为主，仓库承认评分质量依赖所选模型，不能拿示例分数当质量证据 |
| [GrillKit](https://github.com/GrillKit/grillkit) | 技术题库、已掌握题标记、计时练习、理论与编码组合、可选语音；Apache-2.0 | 固定题库与生成题互补；练习模式和测试模式分开；避免反复考已掌握题 | 代码执行依赖 Judge0，扩大隔离、运维和安全责任；其 README 也提示部分环境的运行限制 |
| [DevPrep](https://github.com/juandavidperez/DevPrep) | 技术/编码/系统设计/行为四类题，题库与间隔复习；语音转写可编辑，文字/语音可切换；MIT | 可编辑转写和低摩擦切换，防 ASR 错误被当成候选人回答；题库的复习队列 | 仓库规模很小，公开功能需实测后再判断稳定性；不应因为覆盖面广就全量复制 |
| [Code Defense](https://github.com/AsadullahSamo/Code-Defense) | 根据用户提交的 GitHub 仓库或 PR 生成项目答辩题，按清晰度、深度、正确性、沟通维度反馈 | 为“简历项目深挖”接入用户自己的实际工程材料，而非泛泛问技术名词 | 查询时 GitHub 未识别到许可证；只借鉴概念，不复制代码。远程仓库读取还需授权、文件范围和隐私控制 |

以上开源许可证是仓库公开标示的快照，真正复用代码前仍须核对对应版本的文件与依赖。2026-09-27 查询的 GitHub API 显示 DeepInterview、FEMentor 最近推送在 9 月，GrillKit 在 8 月；另几个小仓库最近推送在 4—6 月。活跃度只影响参考优先级，不证明实现质量。元数据入口：[DeepInterview](https://api.github.com/repos/ngoanpv/DeepInterview)、[FEMentor](https://api.github.com/repos/Jye10032/fementor)、[GrillKit](https://api.github.com/repos/GrillKit/grillkit)、[AI Interview Simulator](https://api.github.com/repos/ZhenyuanPAN822/ai-interview-simulator)、[DevPrep](https://api.github.com/repos/juandavidperez/DevPrep)、[Code Defense](https://api.github.com/repos/AsadullahSamo/Code-Defense)。

## 四、论坛信号：用户真正抱怨什么

1. **反复练习不等于能力提高。** 一位求职者担心自己只是把答案说得更漂亮，却没有更好地解释经历。[原帖](https://www.reddit.com/r/careerquestions/comments/1vj1k2z/are_ai_mock_interviews_actually_helping_or_am_i/)。启示：记录同一能力点的首次回答、复练回答和证据变化，不展示单纯的“练习次数=进步”。
2. **模型改写会让反馈变得不真实。** 有用户反映把即兴口述和模型事后精修稿比较会造成挫败感。[原帖](https://www.reddit.com/r/interviews/comments/1wcrpcj/ai_interview_prep/)。启示：报告优先指出“这题漏掉了哪项信息”，必要时给可选示例；不要默认把每个回答重写成完美范文。
3. **只问固定题缺少真实压力和追问。** 有用户把若干工具形容为“问答聊天”，希望能模拟停顿、打断和追问。[原帖](https://www.reddit.com/r/interviewpreparations/comments/1qvl2af/has_anyone_used_ai_mock_interview_tools_do_they/)。启示：追问质量优先于面试官头像；压力场景应可选，先保证用户可停止和语音稳定。
4. **技术场景更偏任务与决策。** [Hacker News 的系统设计模拟产品讨论](https://news.ycombinator.com/item?id=39055841)将白板、口述设计和即时反馈结合；这提示技术岗面试还需要考察权衡过程。该讨论只有少量评论，不足以证明需求规模。

论坛帖包含低票数、营销或自荐内容；以上均是待验证需求假设，不是独立市场调查结论。

## 五、与 Rehevo 当前代码的差距

| 方向 | 当前已具备 | 真正缺口 |
| --- | --- | --- |
| 岗位上下文 | 文本出题可用简历、Skill、自定义 JD；有能力计划 | `InterviewHubPage` 在文本自定义方向传入 JD，语音配置未传 JD；没有贯穿材料、面试和复盘的岗位目标容器。知识库问答仍主要是独立入口 |
| 实时追问 | 预生成追问与 `LiveFollowUpDecisionService` 的 `DEEPEN/ADVANCE` 建议 | 默认仅观察；关键点是字符串包含判定，尚不能证明追问捕捉了语义缺口或实际改变题序后效果更好 |
| 评分与训练 | Rubric、回答原文证据、失败/未答区分、训练任务与弱项优先级 | 30 条评分 seed 仍为 draft；修复后仅一次 30/30 复验，不能宣称稳定和准确；没有经人工复核的跨场改善结果 |
| 复测 | 计划侧 `retestCoverage` 与历史重点匹配，前端可看到计划 | 分子是“实际题单中被优先标记的能力点”，不是候选人答对、评分提高或真人认可的能力提升 |
| RAG | 混合召回、来源快照、候选证据展示；已有历史 45 题 RAGAS 忠实度记录 | 多段 Gold v3 仍未人工复核，证据门仍 OBSERVE；97.68% 只属于历史固定 RAG 问答样本，不对应面试训练效果 |
| 语音 | WebSocket Turn 协议、取消确认与 Fake Provider 故障矩阵 | 缺同配置至少 20 次真实正常 Turn 的完整基线与前后对照；不能宣称延迟或成功率改善 |

代码定位：`InterviewQuestionService.java`、`InterviewPlanService.java`、`LiveFollowUpDecisionService.java`、`frontend/src/pages/InterviewHubPage.tsx`、`frontend/src/components/InterviewChatPanel.tsx`；阶段和证据见项目根目录的 `TODO.md`、`TECHNICAL_EXECUTION_PLAN.md`、`DELIVERY_EVIDENCE.md`。

## 六、建议路线：先对齐基本体验，再形成技术岗特色

| 候选改造 | 用户价值判断 | 实施与维护成本 | 决策 |
| --- | --- | --- | --- |
| 岗位目标容器、快速样例、复练入口 | 高：减少功能散落与首次配置负担 | 小到中；主要是数据关联与前端路径 | 先做，作为后续能力的共同入口 |
| 项目材料驱动的技术答辩 | 高：与技术面试的真实追问更接近 | 中；材料选择、来源追踪、事实风险需控制 | 选一个岗位族做小样本实验 |
| 人工复核评分与跨场证据 | 高：决定报告是否可信、能否说训练有效 | 中；标注时间和重复运行成本不可省 | 与项目答辩并行，先于效果宣传 |
| 真正改变题序的实时追问 | 潜在高；若相关性差会伤害体验 | 中；需标签集、回退和时延预算 | 文字模式先做受控对照 |
| 语音 Turn 体验与转写纠错 | 高：影响会话能否完成 | 中；已有协议基础，真实 Provider 测试有成本 | 先验收基线，再针对瓶颈优化 |
| 代码运行、Avatar、全行业与多语言 | 当前价值不明 | 高；新增安全面、依赖和内容维护 | 暂缓 |

上述“高/中/小”是基于公开竞品与当前代码的规划判断，不是用户调研结果；第一轮真实用户实验可以推翻它。

### P0：一个目标、一条训练路径（小到中等改造）

把岗位/JD、简历、选定项目材料、练习场景、历次会话和训练任务挂在同一目标下。首页提供“试一轮技术项目深挖”样例，不要求用户先上传真实简历或配置完整知识库。复盘页有“练这个薄弱点”的明确入口。文本与语音共用同一岗位上下文；先检查语音 JD 缺口。

**验收**：新用户能从目标创建完成一场练习并返回复练入口；检查文本/语音两种模式实际使用的目标材料一致。记录首次出题所需步骤、放弃点和完成率，不预设“上线即提升留存”。借鉴：[FinalRound 的 Goal 与继续练习](https://docs.finalroundai.com/docs/mock-interviews/starting-a-mock)、[DeepInterview 的快速体验](https://github.com/ngoanpv/DeepInterview)。

### P1：技术项目答辩（中等改造，最有潜力成为项目主线）

首版只支持用户明确选择一份简历项目与少量材料，不自动抓取任意仓库。题目围绕“谁用、业务流程、本人负责、关键设计、失败路径、数据口径和替代方案”展开。每道题保留材料哈希、来源片段和 Rubric；资料没有的信息明确标为待核实，不由模型补造。为 Java/后端/AI 应用岗先做一个小而可信的题型包，再考虑其他岗位。

**验收**：人工审阅固定项目材料上的题目是否紧贴真实项目，答案是否能被源材料支持，是否能暴露真实的解释缺口；报告中的来源卡片可回到原文。借鉴：[Code Defense 的仓库上下文](https://github.com/AsadullahSamo/Code-Defense)、[牛客的项目介绍练习入口](https://www.nowcoder.com/interview/ai/index)。

### P1：评分可信与跨场复测（中等改造，先于宣传效果数字）

人工复核现有 Gold，冻结开发/测试分组；对评分覆盖、重复运行、回答证据、事实风险分别报告。复测时用同能力不同题面，保存前后两次原始回答和评分依据。请技术面试经验者在不知道哪次是“优化后”的情况下评价完整性、具体性和事实一致性。

**验收**：原计划中的评分稳定门槛先满足；探索性跨场样本至少包含真实候选人和人工盲评。若真人没有更偏好复测回答，或系统只让回答更长、更像模板，则停止宣传“能力提升”，回到题目与建议质量。研究提醒：LLM 评委会出现[位置偏差](https://aclanthology.org/2025.ijcnlp-long.18/)等不稳定因素，不能只用自身分数证明自身有效。

### P2：由回答触发的追问（中等改造）

沿用当前可测试的决定器，但增加语义缺口、事实追问和同义重复的人工标签集。先在文字模式小范围开启：为什么深挖、为什么换题均有固定原因，最多追问次数和时长预算明确；模型失败时回到固定题序。人工评审确认追问的相关性和非重复性后再进入语音。

**验收**：与固定题序同题材对照，人工评审追问相关性、重复率、额外耗时、完成率；不能仅报告“模型生成了追问”。借鉴：[AI Interview Simulator 的显式策略](https://github.com/ZhenyuanPAN822/ai-interview-simulator)。

### P2：语音练习可用性（现有工程主线）

优先做真实 20 Turn 基线、错误恢复、可编辑转写、停止/重听/文字切换；只有观察到自然对话被端点判定破坏，再评估 VAD、打断或更复杂的实时栈。语音优化同时看首音频 P95、整轮 P95、成功率、用户纠错次数和完成率。[LiveKit 文档](https://docs.livekit.io/agents/logic/turns/tuning/)也将端点判定和中断视为需按场景调节的问题，而非一键功能。

**验收**：同配置前后对照；取消旧音频泄漏数为 0；ASR 错误能够被用户纠正且不会被误评分。借鉴：[DevPrep 的可编辑转写与模式切换](https://github.com/juandavidperez/DevPrep)。

### 暂缓项

- Avatar、多面试官和情绪识别：开发与验证成本高，当前没有证据表明比追问质量、评分可信度更有价值。
- 直接接入 Judge0/在线代码执行：会新增不可信代码隔离面，且 [Judge0 曾披露沙箱逃逸相关安全问题](https://github.com/judge0/judge0/security/advisories/GHSA-q7vg-26pg-v5hr)。先用静态代码片段、设计题和口述权衡完成技术面试验证。
- 大型题库、全行业覆盖、多语言、SaaS 支付：容易稀释技术岗特色；先证明一个目标岗位场景。
- 只靠 LLM 分数或历史 RAGAS 数字写“面试效果提升”：两者测量对象不同。

## 七、第一轮可执行实验

1. 选择 5—8 名目标用户（校招 Java/后端/AI 应用岗），每人完成一次当前版练习；观察从创建到复盘的路径，收集最有用/最不可信的一条反馈。
2. 选 2—3 个经同意使用的项目，冻结简历片段与可公开材料；请熟悉技术面试的人标记 20—30 条值得追问的问题、证据与错误回答类型。
3. 对同一材料比较当前通用出题、项目证据出题、人工题单；盲评岗位相关性、项目真实性、追问价值、重复率。
4. 先完成评分 Gold 人工复核和 3 次真实重复运行，再做首轮跨场复测。把“题单覆盖”“实际追问”“回答证据改善”“用户自感有用”分别记录。
5. 在独立样本和真实 Turn 基线出现之前，不把探索性数字写进简历或 README 效果成果。

这轮实验的出口应是一份真实用户路径录像/记录、题目与报告样例、人工评审原始表和明确失败案例。若项目证据题并未比通用题更受真人认可，优先改材料选择和题型，而不是扩张模型与技术栈。
