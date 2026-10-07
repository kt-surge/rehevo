# 训练目标到定向题：实现、对照与质量审查

## 本轮结论

保留训练目标定义与题目关联的数据链路：完整传递练习动作和完成标准，以稳定目标 ID 关联主问题，拒绝未知/多个 ID，同名、多个题名、追问及降级题不能制造新题的覆盖。计划持久化目标定义和实际主问题索引；准备阶段页面改为“已安排题目”，不宣称能力改善。旧 JSON 缺字段可正常读回。

**整体出题/评分质量仍为 HOLD，停止扩大本批真实模型样本。** 本轮只有同默认 Flash 的一组开发对照，已曝光输入、固定候选→基线顺序；不能写成正式质量提升、学习效果或性能结果。原 S0–S4 与语音/RAG 门保持不变。

## 源码及回归

TrainingTargetSelector 按优先级选择最多三个去重能力重点。目标定义由能力描述、练习动作、完成标准决定；旧题号、优先级、待复核原因变化不改变 ID。QuestionDTO 返回 trainingTargetIds，服务端允许每个主问题最多关联一个本次目标。Guide 的显式声明状态区分新题未关联与旧题缺字段。旧题单保留精确显示名兼容。

| 阶段 | 实际执行 | 结果 |
| --- | --- | --- |
| 冻结旧 Plan + 仅供装配的新字段桥接 | 5 个回归用例 | 4 失败、1 通过；非编译失败 |
| 候选 r1 完整后端 | 91 suites / 451 tests | 450 通过、1 失败：Jackson 不能将旧 JSON 缺失值读入新增 primitive boolean |
| 候选 r2 完整后端 | 91 suites / 451 tests | 451 通过、0 skipped；新增字段用可空入口并在构造器归一化 |
| 前端 pnpm build | 实际构建 | 通过，仍有既有 chunk 大小提示 |

零模型回归覆盖显示名变化、单目标重复计数、未知/多目标、显式未关联、追问排除、目标定义稳定性、null/去重/选择上限、完整出题提示装配、降级题不冒领及旧 JSON/新状态持久化。本轮没有新浏览器交互；完整 S4 浏览器产品流程尚未验收。

## 真实默认模型对照

输入是此前封存的公开受控评分预检产生的两项任务，不含真实简历、JD、会话；相同 Java Skill、mid、3 个主问题、每题 1 个追问。同一默认 qwen3.8-flash/现有阿里云接口、中央严格 Schema/最多两次尝试。两组均观测到 1 个 SDK Chat operation，无修复重试。SDK operation 不是供应商线上 HTTP 请求计数。

基线通过独立 JavaCompile/类路径优先加载冻结的旧 Guide、Plan、QuestionService、PlanService 和旧模板，未覆盖生产源文件。runtime-config.json 证明基线从 training-target/baseline 加载；候选使用当前 app classes。输入字节相同；训练重点之前的分布/难度/历史输入、参考题库与输出要求、Skill persona/通用模式相同，仅归一化随机安全边界标记和 CRLF。

| 项目 | 旧实现 | 候选 |
| --- | ---: | ---: |
| 生成主问题 / 追问 | 3 / 3 | 3 / 3 |
| 计划记录的目标关联 | 0 / 2 | 2 / 2 |
| 实际关联索引 | 无 | 消息边界→4；代理边界→0 |
| Input Tokens | 3,279 | 3,687 |
| Output Tokens | 1,767 | 2,027 |
| Total Tokens | 5,046 | 5,714 |
| SDK Chat operations | 1 | 1 |
| 单次生成耗时 | 32,332 ms | 33,085 ms |

实际合计 **10,760 Chat Tokens / 2 SDK operations**；本批无跨模型、Embedding、ASR、TTS。启动命令两次装配错误均在 Spring/模型调用前失败，保留各自日志，不计模型调用。数据范围前后相同：公开材料 15 文档 / 123 向量，文字/语音/RAG 会话及消息均 0，原公开向量哈希不变。

0/2→2/2 证明旧名称匹配丢失关系、候选能够保留关联；旧生成问题本身也涉及代理和消息幂等，不能说旧实现未考这些能力。候选的代理主问题明确自调用，消息主问题明确业务处理成功但 ACK 失败，较旧主问题泛问更贴近任务；只有一个开发案例，不推断总体收益。ID 匹配不是内容/评分事实核验。

## 逐项技术审查及下一步

1. 两组 type 均为 CORE / CORE / ALWAYS_ONE，违反给定分类 key（JAVA、MYSQL、PROJECT 等）的契约。源码定位到 buildAllocationDescription 只给显示名与优先级，未给 key。需补明确 key 和服务端合法分类校验，避免把优先级当题型。
2. 候选 MySQL 题同时 user_id/status 等值过滤，rubric 却将 status 在 user_id 前判作错误。根据 [MySQL 8.4 ORDER BY 官方说明](https://dev.mysql.com/doc/refman/8.4/en/order-by-optimization.html)，索引前缀常量可保留后续排序；这里两种等值前缀顺序都可能可行。这个结论是按题设作出的推断，实际效率还需 EXPLAIN、数据分布和其他查询。不能把一种合法方案判为必错。
3. 候选把“非 public 方法”泛列事务失效场景。[Spring 官方事务文档](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html) 说明：自 6.0 起类代理默认可支持 protected/package-visible；接口代理方法仍需 public。另需区分外层已存在事务、代理模式与 AspectJ，不能将自调用简化成整个业务必然无事务。
4. 消息 rubric 将先 ACK 后处理列为模式但没有明确持久化移交/补偿与丢失边界。[RabbitMQ 官方确认文档](https://www.rabbitmq.com/docs/confirms) 说明 ACK 后消息可删除、未 ACK 的断连投递会重新排队；要求可靠业务消费时应明确提交后 ACK 及重复投递幂等，也需审查 Redis 标记与 DB 更新之间的原子性。现有文字不足以作为可靠性高分依据。
5. 旧题有“你在实际项目中”措辞，与无简历通用模式约束冲突；旧/新训练原因还有未验证认知的推断，不能据此认定真实能力缺口。题目/Rubric 应依据可验证技术边界，待复核原因与候选人已承认事实分开。

下一步先修分类输入/校验，再核查注入参考资料及评分边界；规则改变后用新的受控测试资料做质量验证。继续完整文字/语音→评估→训练→文字复测产品链路，并处理已知文字删除缓存、语音评估同类事务调用。语音正式浏览器及 RAG 生成/引用/成本门未通过，本轮不宣称第一轮优化完成。

## 可复跑证据

实验目录：observability/experiments/voice-frame-pipeline/runs/training-target-{baseline,candidate,live-candidate,live-baseline}-20261005-r*。完整结果见 JUnit XML、源文件/模板快照、输入、实际供应提示词、解析结果、questions/plan、runtime-config、逐类型错误、实际用量及数据范围。冻结版本不覆盖；校验以最终 SHA-256、成员集合及本机现有密钥精确值扫描为准。

## 后续补记：分类修复与最终现场

分类 key 已补入分配表，简历题补允许 key 清单；服务端只能按本次给定 key/显示名精确恢复优先级误填，双未知则拒绝。旧行为本类 7 项/3 失败，修复后实际完整 **91 suites / 454 tests 全通过、0 skipped**，本步零外部模型调用。细节在 QUESTION_TYPE_CONTRACT_RESULTS_2026-10-05.md；两份上表模型输出是类型修复之前的观测，新模板尚未做真实预检。

两段共七版 / **547 文件**封存，独立哈希、成员集合、精确密钥值扫描无差异/无命中；旧封存目录未修改。type-candidate 的 21 份当前 app 源码/测试/模板与冻结版本 hash 一致。隔离 App 已重新启动并现场验证 main、workspace manifest、Spring AI 2.0.1/SDK 4.49.0、编译 class 早于进程创建及 health UP；现场 PID 19640 / exec 54840。旧 App 26519/25927 已结束，未来停止前须重新查 owner，不能按本文 PID 直接操作。

数据仍为 15 文档/123 向量/0 文字、语音与 RAG 会话消息，原公开向量 SHA 不变；默认 Flash、ASR 3.0/2000ms、TTS 3.1/longanhuan_v3.1 保持。恢复时的入口路径错误、PowerShell 验证脚本解析错误和重复启动日志文件锁失败已纠正；未发起模型调用，最终现场证明单一 owner 和当前编译代码。总体目标 active，语义质量 HOLD，原 S0–S4 必选阶段持续推进。
