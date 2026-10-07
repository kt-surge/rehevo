# 新原文事实开发集

**当前决策：真实入库、候选/上下文基线及三种既有策略诊断已完成；保留默认 HYBRID，继续结构切块与预算实验。新 test 与生成评测尚未完成。** 见 [完整基线报告](PRIMARY_DEV_BASELINE_RESULTS_2026-10-01.md)。

## 数据与来源

当前包：`data/local/fact-gold-v1-20261001-r2/manifest.json`。

- 4 份官方原文 HTML 及完整主体解析文本，共 80,884 个规范文本字符。
- 34 题：26 条可回答、8 条资料不足；可回答题包括 2 条跨文档要求。
- 39 条原文事实，保存 Unicode 字符区间、逐字引文、原始文档与规范文本双哈希。
- 中文问题、英文资料，单独标记这类跨语言条件；不能把结果推广为中文用户知识库整体效果。
- `agent_verified` 是同一 Agent 在制题时进行的来源及条件自审，非人工或独立评审。尚未建立新 test split。

| 文档 | 版本边界 | 一手来源 |
| --- | --- | --- |
| PostgreSQL 事务隔离 | 17 | [官方文档](https://www.postgresql.org/docs/17/transaction-iso.html) |
| PostgreSQL pg_trgm | 17 | [官方文档](https://www.postgresql.org/docs/17/pgtrgm.html) |
| Redis XREADGROUP | 2026-10-01 下载快照；包含新版本选项，不等同于项目部署版本 | [命令参考](https://redis.io/docs/latest/commands/xreadgroup/) |
| Spring 事务注解 | 2026-10-01 下载快照；题目显式保留 6.0/6.2 等版本条件 | [官方文档](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html) |

原始包 `fact-gold-v1-20261001` 保留，没有运行评分。二次语义检查发现若干事实区间需要包含 NOACK、gist_trgm_ops、@Transactional 主体，因此显式建立 r1；没有通过覆盖已冻结文件修改标签。

当前清单 SHA256：

`f0c89483c7a092e7b21d79d3b1f9c229fce254fc4aee7e2ccb39287978020f0f`

r2 在质量评分前修正三条说明文字的跨片段空白误判，未改变原文、事实位置、问题或参考答案。r1 及其 36/39 的严格字符保留诊断仍保留；r2 的实际事实保留为 39/39，不称为检索召回成绩。

## 工具验证

`fact_gold.py` 已实现：来源哈希及逐字区间校验、必要条件 AND/替代证据 OR、跨相邻块连续范围覆盖、实际 Chunk 文本校验、候选/最终上下文分别评分、逐条件首次满足排名、失败与缺失保留分母、开发/测试隔离及复核主体门。

必要条件覆盖同时输出每题平均与按条件数加权的总比例。`allRequiredMeanReciprocalRank` 是候选前缀第一次共同满足所有条件的倒数排名均值，不能标为普通单相关项 MRR。不可回答题的检索覆盖为不适用；检索非空不视为拒答成功或失败。

最新 25 项契约回归通过，记录见 `contract-tests-20261001-r2.txt`；前版 20 项记录也保留。这些样本全部是测试 Fixture，仅证明工具边界；新开发包的自动契约验证另见包内 `validation.json`，不能代替独立语义复核。

## 真实组件预检

`runs/embedding-preflight-20261001.json` 保存一次使用当前 classpath 默认 DashScope 配置的真实请求：`qwen3.7-text-embedding`、1024 维，HTTP 200、有限且非零向量，26 个输入 Token。密钥没有进入文件或输出。

该预检只确认当时凭据可用于该组件。此后已启动独立应用并采集合并持久化 Provider 设置后的配置；真实检索结果另见基线报告，不把预检当作延迟改善证据。接口/维度依据 [阿里云官方 API](https://help.aliyun.com/en/model-studio/text-embedding-synchronous-api) 核对。

## 接续步骤

最新进展：[结构切块真实对照](STRUCTURE_DEV_RESULTS_2026-10-01.md) 已完成同原文/同模型的真实重新入库和 34 题扫描；最终完整覆盖仍为 25/26，同预算无收益，默认切块不变。原始 24 块已精确恢复。后续优先核对解析的格式保留、扩大开发材料和多条件失败切片，再准备未暴露测试材料。

1. 启动独立实验 PostgreSQL/pgvector、Redis、对象存储与应用，记录实际生效配置与容器镜像指纹。
2. 当前上传不接收 HTML，因此导入规范 TXT；在运行清单中分别保存官方 HTML 来源哈希、规范 TXT/实际上传哈希与 Tika 解析后文本哈希，不把不同文件的哈希视为相同。
3. 从实际入库 Chunk 导出每个变体及原文映射。映射必须通过实际文本校验；无法对齐的格式变化先诊断，不能忽略后继续给分。
4. 固定模型及 Gold，先跑当前 HYBRID 基线，保存候选与最终上下文；再依据失败题逐项比较结构切块和上下文预算。
5. 准备未暴露的新测试文档/主题；仅在策略冻结后运行，避免用这 34 条开发题作为泛化验收结论。

下载、原文事实及题集均留在忽略目录 `data/local/`；冻结/构建工具留在实验目录。两份构建工具是本次一次性数据准备，默认拒绝覆盖已存在包，修正需明确新版本。
