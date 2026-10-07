# Skill 参考材料事实边界修复与同模型对照

## 当前问题与方案

源码把共享 references 直接用于出题，也用于文字/语音评估。Spring 材料将所有非 public 方法列为事务失效、将命令行参数配置优先级列为最低；MySQL 将 OR 和非最左列一概列为索引失效，并将 RR 当前读概括成必有间隙锁；MQ 将 RocketMQ 事务消息等同于恰好一次、未说明外部数据库与确认/去重的原子边界。已有真实 Rubric 输出出现了相关误判。

只修 `spring.md`、`mysql.md`、`mq.md` 的事实和适用条件，保留主题范围，添加版本/官方依据。出题 Prompt、Schema、分类规则、训练目标、模型和限额不作为 A/B 变化项；不堆新的提示规则。参考文件必须在现有单文件 3000 字符上限内；逐案例保存实际注入内容，不能用磁盘文件存在证明模型收到事实。

## 官方依据

| 修订依据 | 明确边界 |
| --- | --- |
| [Spring 事务代理](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html) | Spring 6+ 类代理可支持 protected/package-visible，接口代理仍需公开接口方法；同类调用不触发新的注解拦截，不等于外层事务不存在 |
| [Boot 配置](https://docs.spring.io/spring-boot/reference/features/external-config.html) | 正常默认加载下后序来源覆盖前序，命令行高于环境变量和配置数据；同位置 properties/yaml 的规则须与跨来源优先级分开 |
| [MySQL 8.4 排序](https://dev.mysql.com/doc/refman/8.4/en/order-by-optimization.html)、[Index Merge](https://dev.mysql.com/doc/refman/8.4/en/index-merge-optimization.html)、[InnoDB 锁](https://dev.mysql.com/doc/refman/8.4/en/innodb-locking.html) | 等值前缀可支持后续列排序；OR 可采用 Index Merge；完整唯一键定位现存单行与范围/非唯一条件的锁不同 |
| [Kafka 4.1 语义](https://kafka.apache.org/41/design/design/)、[RabbitMQ 确认](https://www.rabbitmq.com/docs/confirms)、[RocketMQ 事务消息](https://rocketmq.apache.org/docs/featureBehavior/04transactionmessage/) | Kafka 内部读写/offset 事务不自动覆盖外部 DB；ACK 后业务失败和提交后 ACK 前崩溃的边界不同；RocketMQ 事务消息不免除消费者幂等 |

Agent 根据官方资料复核，非独立人工或数据库供应商实测；没有引入第三方代码。

## 冻结实验

四个公开受控案例：Boot 同时有 YAML/环境变量/命令行；Spring 6+ 类代理的 protected 方法；MySQL 两个等值列与后续排序；MQ 提交业务数据库后确认前崩溃。已出现的主题是开发诊断，不作为正式未曝光质量集。

真实生产 QuestionService / SkillService / Registry / StructuredOutputInvoker 路径，单类别 Skill 夹具，两臂只替换参考文件只读加载路径。无简历/JD/真实历史，训练任务是人工写的受控练习定义；不创建产品会话，不调用 ASR/TTS/Embedding。保持 qwen3.8-flash；这是同模型材料对照，不再使用已耗尽的八次跨模型授权。

先一对 Boot 问题预检；成功且材料/选项一致才扩至四对，2 AB / 2 BA。每臂一题、无追问，中央 structured 最大尝试 1；SDK 网络重试仍按原配置，记录实际操作/Token，不假称网络请求数。最多八个生成操作，累计观测 Token 30000 为停止阈值，任何身份/额度/结构持续失败停止该批。若来源修订没有可证实收益，不把其写成模型准确率提升。

逐题自审题目相关性、真实技术边界、Rubric 条件、合法分类/训练目标和无虚构候选人事实；保留原始提示/结构化输出、负结果与实际 usage。另准备固定正确/错误回答的评分对照，只有预检后再推进。生成与评分结果分开，不用题目合法性推断公平评分。

现有完整后端回归用于模板/加载兼容；正式语音、RAG 检索/生成/引用/成本门和完整产品/故障门保持。来源修复不能替代正式 RAG 质量或端到端语音收益。
