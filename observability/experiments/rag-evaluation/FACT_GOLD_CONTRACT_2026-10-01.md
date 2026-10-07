# 原文事实金标契约

用途：比较不同切块/上下文策略时，问题与必需事实保持不变。旧 v3 撤回结果不恢复；本文不提供新的检索分数。

## 1. 来源冻结

文档清单保存 `documentId`、原始文件 `documentSha256`、解析后规范文本 `sourceTextSha256`、解析器版本、来源类型、`topicGroup` 与 `split`。

事实位置指向冻结的规范文本：`factId + start + end + exactQuote`，区间为 Unicode 字符位置的左闭右开区间。校验规范文本的 `[start:end]` 必须等于原文引文；不得用“同文档/同章节”代替事实证明。原始文件与规范文本的哈希均校验。

来源类型至少区分用户材料、项目技术资料、公开一手文档、受控 Fixture。受控档案不能表述成真实用户简历或生产记录。

## 2. 必需条件与替代证据

一个问题含多个 requirement，各 requirement 必须满足（AND）；每个 requirement 可以有多套替代证据（OR）；一套替代证据可以需要多个事实共同支持（AND）。

```json
{
  "id": "example-dev-001",
  "split": "dev",
  "question": "配置变更的生效条件是什么？",
  "answerable": true,
  "requirements": [
    {
      "id": "condition",
      "alternatives": [
        {"factIds": ["configuration-value", "activation-condition"]},
        {"factIds": ["complete-configuration-summary"]}
      ]
    }
  ],
  "referenceAnswer": "此处放完整、受到原文支持的参考答案。",
  "referenceFactIds": ["configuration-value", "activation-condition"],
  "review": {"status": "draft", "reviewerType": "none", "reviewer": null}
}
```

这是契约示例，不是评测样本。参考答案中的每个必要事实需能回到引用；纯结构校验不能证明参考答案完整或无语义错误。

不可回答问题：requirements 为空，给出缺失属性/超范围理由，注明搜索过的资料范围；不强行指定一个相关 Chunk 当正确证据。

## 3. 每种切块策略独立映射

每个变体保存 `strategyFingerprint`、文档与规范文本哈希、Chunk ID/index、父块、原文范围、实际检索文本和事实覆盖映射。

- 事实完整落在某块时，可由该块单独覆盖。
- 事实被切开时，允许相邻块的原文区间联合覆盖；必须检查连续内容和实际文本，不靠范围数字假定覆盖。
- 默认缺口仍失败。明确复核为普通说明文字的事实可标注 `textRole=documentation_prose` 与 `allowInterChunkWhitespaceGap=true`，仅允许跨不同块的纯空白缺口；单块删除、代码/字面量或实质内容缺口不能通过。r2 的三项修正及原始版本均保留，不把格式误判当作算法改善。
- 模型生成的摘要/上下文前缀不是原文事实，不得自动作为正确证据。
- 检索候选、最终送入模型的上下文分别评分；邻段扩展只有真正进入上下文才计作生成可用证据。

旧 `expectedChunkRefs` 平铺结构无法完整表达替代证据和跨块联合支持。新评分入口按 requirement 计算，旧评测入口保留历史兼容，不把两种结果合并。

## 4. 指标

- requirement coverage：每题的已满足 requirement 数 ÷ 必需 requirement 数；报告区分题目等权平均和所有条件数的总比例（micro），不混用分母。
- all-required Recall：所有 requirement 同时满足的可回答题数 ÷ 可回答题数。
- 排名：保存第一次能满足某 requirement 的排名、第一次共同满足全部条件的排名；MRR/nDCG 的相关性等级规则在运行前冻结。
- 无答案：相关片段命中与“有足够证据回答”分开统计，不能以检索非空视为拒答失败或成功。
- 生成：同一 requirement 口径计算必要事实覆盖，再测断言忠实度和引用支持；参考答案事实与检索上下文事实不能混作一项指标。

## 5. 数据门与复核主体

自动门：唯一 ID、哈希/原文位置正确、fact 引用合法、参考事实能满足 requirement、答/拒答结构、文档/主题隔离、split 与清单计数一致。

语义门：逐条核实问题到底问了哪些属性、条件/例外是否必要、参考答案是否包含全部必要事实、替代证据是否真的等价、无答案题是否在语料中缺失。之前 v3 漏标同文档属性的问题必须在这里发现。

复核状态分 `draft / agent_verified / human_verified`。Agent 复核不能导出旧契约的人工 `reviewed` 标志。`agent_verified` 可用于明确标注的内部诊断；正式人工复核结论另需确认。冻结后记录清单哈希，开发只看 dev 结果，test 只在策略冻结后运行。

## 6. 接续任务

1. 已实现 `fact_gold.py` 的自动校验与 requirement 评分；最新 25 项正负边界回归通过，全部标记为契约 Fixture。
2. 已冻结四份官方文档与 34 条开发题、39 条原文事实；同 Agent 自审，非独立/人工复核。见 [开发集状态](PRIMARY_FACT_DEV_STATUS_2026-10-01.md)。
3. 准备按文档/主题隔离的新测试材料，保存复核主体；不在旧已暴露 test 上调参。
4. 实际入库映射及三种既有策略的真实开发诊断已完成，见 [基线报告](PRIMARY_DEV_BASELINE_RESULTS_2026-10-01.md)。继续结构切块和上下文预算实验，不将本开发集当作泛化验收。
