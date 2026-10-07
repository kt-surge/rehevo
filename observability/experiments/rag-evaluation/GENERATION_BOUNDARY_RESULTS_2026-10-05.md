# RAG部分回答与短答SSE修复结果

## 已修复的用户问题

旧后处理将任意位置出现“信息不足”等短语的整段回答替换成“未检索到相关信息”，会吞掉已经有依据的部分答案。旧SSE还缓存120字符，短答只能等结束再发，且会在缓存阶段因为不足说明取消整段答案。

改为只对空/纯空白使用原空答提示；其他答案保留事实和拒答理由。流式首正文立即交付，前导空白/Markdown顺序保留，取消和错误交由Reactor操作符传播。不改检索、模型、Prompt、证据门或数据库结构。

## 回归对照

先冻结旧实际源码，运行同一7项行为测试：旧7项中6项失败、1项通过，失败结果保留。修复后75套件、370项full fresh全量后端通过，失败/错误/跳过均0。部分回答、自然拒答、跨Token不足说明、短答未完成时首片段、空白与Markdown、空流、上游失败和取消均验证。

证据：[旧回归](runs/generation-boundary-20261005-r1/old-regression-summary.json)，[全量新回归](runs/generation-boundary-20261005-r1/fresh-all-tests-summary.json)。这些是受控正确性测试，不是RAGAS、网络性能或模型准确率。

## 真实模型与产品接口

仅公开Redis XREADGROUP文档，既有知识库ID3，qwen3.8-flash与1024维Embedding。新源码/问题先冻结，不更改旧索引。一次实际回答评测接口，rewrite=false、HYBRID、8个实际证据：回答保留NOACK与PEL的已有结论，同时说明生产重试秒数信息不足。

对**同一份实际非空模型回答**代入旧后处理精确谓词，旧逻辑会整段改为未检索到信息，新逻辑保留答案。是一次生成与旧逻辑离线等价投影，不是两次模型调用或旧应用在线对照；旧真实方法的失败已有前述测试验证。证据：[同回答对照](runs/generation-boundary-20261005-r1/same-answer-normalization-comparison.json)。对NOACK结论人工自审核对了冻结公开原文；不称独立人工忠实度评分，文档为2026-10-01保存的最新参考，不代表本机Redis版本支持其所有新选项。

随后一次真实无会话HTTP SSE接口：首非空片段“根据”2字符即交付，最终105字符包含已有事实与信息不足，没有错误输出。旧120字符/关键词逻辑会在本短回答上改成整段拒答。没有新建会话或上传文档。证据：[SSE逐事件](runs/generation-boundary-20261005-r1/real-sse-probe/result.json)，[原始SSE行](runs/generation-boundary-20261005-r1/real-sse-probe/raw-sse-lines.jsonl)。

这一次请求首正文观测2261ms，最后SSE行3079ms，仅说明首片段先于结束、无需凑满120字符；没有旧在线SSE计时、不计算延迟改善比例，也不称浏览器首字或生产P95。原采集`endedMs`含最后metrics读取，额外审计明确记录；后续脚本已修复计时位置，没有为了数字更好重跑。网络/HTTP分片不等于供应商原始Token分片，文本字节保持另由组件回归验证。

两次实际请求最终供应商usage全部可见：同步Chat8840/Embedding61 Token，SSE Chat8791/Embedding57 Token；合计Chat **17631**、Embedding **118**。SSE默认路径没有额外Query Rewrite Chat。证据：[实际用量与时刻边界](runs/generation-boundary-20261005-r1/actual-probes-audit.json)。不与不同模型的Voice实验数据混算质量。

## 决策与剩余门

保留此正确性修复，属于实际有益的回答保存及短答交付改善。没有用大量拒答提升忠实度，也没有新增拒答关键词或绕开检索证据门。原四份24块指纹不变，15份/123块、voiceSessions/ragSessions均0；真实无会话SSE只增加选定公开文档的一次提问计数。[数据核对](runs/generation-boundary-20261005-r1/after-probes-public-data.json)。

本目标轮为progress。下一步继续S3固定实际上下文：稳定证据编号/来源、断言引用的存在与支持分开验证、必要事实/条件覆盖、可回答误拒与无答案拒答；冻结参数后用未用于调参的新公开测试资料。语音候选事实/聚焦、正式浏览器≥100/组且≥3窗，以及岗位出题/评估/训练/文字复测和既定故障门仍未完成，原S0–S4保持active。不能用2次生成或370项回归结束完整目标。
