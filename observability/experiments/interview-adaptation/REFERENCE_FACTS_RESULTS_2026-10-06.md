# Skill 参考事实修复、同模型诊断与字符预算验收

## 决定

保留共享 Spring/MySQL/MQ 参考材料的事实修正，以及最终压缩版 r4。正常 Java 岗位参考加载不再截断：评估正文 5970 字符，系统设计正文完整 1539 字符；旧版在相同 6000 字符预算下仅保留该正文 1196 字符。此结论来自实际生产加载方法的离线调用，不能当作模型评分、RAG 召回或用户效果提升。

出题/评分内容质量仍 **HOLD**。四对真实生成使用的是扩写版 r1，最终 r4 尚未做真实评分或出题效果对照。保留原始负结果、所有过渡版本和各版新鲜回归，不把 r1 的观察归到 r4。

## 修复的事实与来源

- Spring：区分类代理、接口代理、外部调用和自调用；自调用不触发新的事务拦截仍可参与外层事务。Spring 6+ 类代理默认可支持 protected/package-visible，有 publicMethodsOnly 等条件；接口代理需公开接口方法。[官方事务文档](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- Boot：默认命令行、环境变量、配置数据的覆盖顺序，与同位置 properties/YAML 规则分开。[官方配置文档](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- MySQL 8.4：OR 可能采用 Index Merge；等值索引前缀与后续排序、优化器成本决策分开；完整唯一键命中现存单行的锁与范围/非唯一条件分开。[ORDER BY](https://dev.mysql.com/doc/refman/8.4/en/order-by-optimization.html)、[Index Merge](https://dev.mysql.com/doc/refman/8.4/en/index-merge-optimization.html)、[InnoDB 锁](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html)
- MQ：Kafka 内部事务不自动覆盖外部 DB；RocketMQ 事务消息不保证消费者业务只执行一次；DB 提交与确认之间的重复窗口、去重与业务的原子边界。[Kafka](https://kafka.apache.org/41/design/design/)、[RabbitMQ](https://www.rabbitmq.com/docs/confirms)、[RocketMQ](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/)

出处与版本范围由 Agent 核查，非独立人工评审，也非数据库/MQ 供应商运行实测。压缩保留修正的关键条件与原有主题，详细来源在本报告和冻结设计中，避免把长链接重复塞入推理参考预算。

## 四对同模型生成：r1 开发诊断

生产 QuestionService、SkillService、Registry、中央 StructuredOutputInvoker；单类别 Skill 夹具，每臂一题、无追问。固定 Flash，中央结构化尝试 1。只替换实际参考正文；源码、模板、Schema、输入及参考之外的提示词一致，仅规范化随机数据边界标记和 CRLF。顺序 Boot AB、事务 BA、MySQL AB、MQ BA。

没有真实简历/JD/用户会话，不创建产品会话，无 Embedding/ASR/TTS。使用持续目标的同模型受控授权，跨模型操作 0；此前专门授权的八次 Flash/27B 对照未再次使用。

| 案例 | 基线 Token | r1 候选 Token | 内容观察 |
| --- | ---: | ---: | --- |
| Boot 配置 | 3278 | 3506 | 两臂均给 8082；候选更明确区分两类规则。基线已正确，不能声称准确率提高 |
| 事务代理 | 3424 | 3473 | 基线错误归因 Spring 5.2 和“6/Boot3 默认切换 CGLIB”；候选写清 6+ 支持条件。两臂仍笼统说自调用“事务失效” |
| 等值索引排序 | 3270 | 3585 | 两臂均识别两种索引的能力；基线额外加入 LIMIT 10，候选保留 8.4 和原始条件 |
| 提交后确认前崩溃 | 3480 | 3665 | 候选写清 MQ 事务不覆盖外部 DB、单独 Redis 去重的风险；其“唯一索引+捕获忽略”仍缺少与业务同事务的条件 |
| 合计 | 13452 | 14229 | 8 个观测 SDK Chat 操作，27681 Token，其中输入 22277、输出 5404 |

八次均完成，分类及训练目标关联合法、原始 DTO 各含五档 Rubric。保存的是送入中央调用器的提示词与已解析 DTO，并非原始供应商 HTTP 报文；SDK 网络重试未捕获，不能把操作数说成网络请求数。调用阶段耗时仅保留逐样本原值，无正式延迟比较。

四个练习已用于开发诊断，且输入的训练完成条件明确提示目标边界，可能帮助模型绕过旧材料错误；不是盲测或正式未曝光评测。内容审阅由当前 Agent 做，不能作为独立人工金标。合法分类/结构化成功不代表评分公平或内容质量通过。

## 正常多类别加载：找到并修复明确回退

生产 SkillService 按类别顺序装入参考，评估在 6000 字符处直接截断。r1 扩写虽纠正事实，却严重挤占后面的系统设计内容，因此不直接采用扩写版。正常 Java Skill 的 MQ 未被配置，MQ 对照是单类别夹具，不声称正常 Java 默认出题已用到 MQ 材料。

| 材料版本 | 完整参考字符 | 是否被评估截断 | 系统设计正文可见字符 |
| --- | ---: | --- | ---: |
| 旧材料 | 6343 | 是 | 1196 / 1539 |
| r1 扩写 | 7465 | 是 | 74 / 1539 |
| r2 压缩 | 6279 | 是 | 1260 / 1539 |
| r3 压缩 | 6048 | 是 | 1491 / 1539 |
| **r4 最终** | **5970** | **否** | **1539 / 1539** |

截断版返回长度 6020 包含额外的截断提示，不能用该数字说预算是 6020。最终 Spring 1024、MySQL 725、MQ 1757 字符，单文件均小于 3000；最终正常 Java 多类别生成/评估参考完全一致，关键修正仍存在。保持现有字符上限，没有修改加载算法或额外增加上下文窗口。此审计只验证材料进入加载结果，尚未证明最终评分器理解/使用这些事实。

## 回归、证据与运行环境

各材料版本在源码冻结后分别运行 `:app:test --no-daemon --rerun-tasks` 并立即复制新鲜 JUnit XML。最终 r4：92 suites / 461 tests，failures/errors/skipped 均 0；不包括独立 integrationTest 任务，不合并重复运行次数当作新增用例数。加载审计无模型、数据库或 App 调用。前版回归通过也不能替代其失败的参考完整性门。

冻结 5 个材料版本、8 个真实生成、4 个回归/正常加载目录；源码清单、配置、输入、实际参考、提示词、已解析输出、逐样本用量、负结果、审阅与恢复记录一同封存。精确成员与 SHA-256 独立复核见 `reference-facts-seal-verification-20261006.json`。现有密钥仅用于原值扫描，不写入证据。数据范围在生成前后相同：15 个公开文档/123 个向量，产品会话/消息为 0，固定公开向量指纹未改变。

重启仅针对实时验证归属的隔离 App。首次启动因旧 Gradle 尚占用日志文件失败；确认旧 App/父会话退出、端口无人监听后再启动，最终健康/源码绑定见 `reference-facts-runtime-verification-20261006.json`。保留失败，不把启动命令本身说成恢复成功。

## 接续任务

1. 最终 r4 与旧材料使用固定相同问题/正确和错误回答，独立评估 Rubric、错判、原文证据与建议；先一对同 Flash 小批预检，再决定扩展。不给评分器发送预期标签；不重用 r1 随机生成题/评分依据来冒充固定问题对照。
2. 验收受控 Skill/简历/JD→文字/语音评估→训练回流→文字复测的完整产品流程，并补六类既定异步故障矩阵。
3. 正式 RAG 的检索必要证据、生成忠实度/完整性/引用/拒答/成本门，以及正式浏览器语音起播/间隙/取消门维持原标准。参考材料加载修复不能替代这些核心验收。

完整 S0–S4 目标保持 active。本阶段为 progress，无需用户提供新信息才能继续。
