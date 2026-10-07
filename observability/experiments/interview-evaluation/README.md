# 面试评分稳定性实验

`rehevo-evaluation-seed-v1.json` 包含 10 组问题、每组三档回答，共 30 条固定样例。它当前只是人工构造的 seed，不是已经完成专家复核的 Gold 数据集，因此不能把期望分数命中率直接写进简历。

## 运行

1. 启动依赖，并设置 `APP_INTERVIEW_EVALUATION_BENCHMARK_ENABLED=true` 后启动后端；该接口默认关闭。
2. 确认默认或指定 LLM Provider 可用。
3. 在仓库根目录运行：

```powershell
.\observability\experiments\interview-evaluation\run-stability-benchmark.ps1
```

可用 `-Provider`、`-Repetitions 1..5`、`-DatasetPath` 和 `-OutputDirectory` 覆盖默认值。脚本输出运行时间、Git 提交、逐题三次得分和汇总指标。

运行前先做不调用模型的结构与指纹校验：

```powershell
.\observability\experiments\interview-evaluation\run-stability-benchmark.ps1 `
  -DatasetManifestPath .\observability\experiments\interview-evaluation\rehevo-evaluation-seed-v1.manifest.json `
  -ValidateOnly
```

运行器会拒绝重复 case ID、缺字段/非法分数区间、不是 `0/1/2` 三档的分组，以及数据文件与 manifest 的指纹、题数、分组数不一致。每次成功或失败运行均写入 `datasetContract`，包含数据集 SHA-256 与复核状态，避免将不同版本的种子或配置混为同一稳定性结论。

## 指标口径

- `evaluationCoverage`：成功评分次数 ÷ 总评分次数；
- `evidenceCoverage`：带有可在回答原文逐字定位证据的成功评分次数 ÷ 成功评分次数；
- `medianScoreRange`：每条样例多次得分极差的中位数；
- `medianRubricRange`：每条样例多次 Rubric 等级极差的中位数；
- `rankingAccuracy`：同组相邻质量档的平均分是否严格递增；
- `expectedBandHitRate`：每条样例的平均分是否落入 seed 期望区间。

## 冻结条件

在把 seed 标记为 Gold 前，需要至少一名人工复核者逐题确认问题、三档答案和期望区间，记录修改，并将开发集与最终测试集分开。调 Prompt 或 Rubric 只能查看开发集，最终数字只运行冻结测试集。

当前 `rehevo-evaluation-seed-v1.manifest.json` 明确为 `draft`。若运行时增加 `-RequireReviewed`，脚本会在请求模型前拒绝该数据集；只能在人工复核完成、更新数据文件 SHA-256 和 manifest 的 `reviewStatus=reviewed` 后开启受控稳定性运行。仅通过 `-ValidateOnly` 或单元测试不构成评分质量或稳定性基线。
