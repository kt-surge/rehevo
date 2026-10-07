# 客户端首收音频与起播采集

## 当前结果（2026-10-01）

**保留计时入口及失败恢复修复，继续真实供应商 A/B。没有语音性能改善结论。**

- 默认后端测试组完整 fresh run：288 项、63 个测试组，0 失败/错误/跳过。需真实基础设施的独立故障/集成门仍按 S4 执行。XML、源码副本与哈希见 `candidate-20261001/`。
- 客户端计时 helper：8 项回归通过；最新生产构建 `tsc && vite build` 通过。已有 Browserslist 与大包提示仍存在。
- 实际 React 页面经应用内 Chromium 验收，使用本机受控协议、固定 24kHz 单声道 16bit WAV 低幅测试音；未使用麦克风、ASR、LLM、TTS 或生产数据。
- 完整协议记录有 7 次提交：PCM 4 次、完整 WAV 1 次、取消 1 次、失败 1 次。前五轮覆盖预定场景，额外两轮为改动/刷新后的正常回复复验。共收到 5 份起播报告，每轮最多一份；取消、失败轮次均为 0。不是 5/7 的产品成功率。
- 页面核对：取消/失败后的注入迟到文本均未展示；失败后可再提交，正常轮次仍可起播。已补齐 `turn_failed` 清理、等待 AudioContext 恢复后再启动、旧播放回调隔离、重试清理旧错误。

本轮浏览器记录验证采集与交互路径。没有测量扬声器物理出声、麦克风、跨浏览器兼容、供应商远端取消或真实网络延迟改善。播放中取消、断连和连续帧排程仍按 S1/S4 执行。

## 指标口径

提交时生成请求号，服务端在 `turn_started` 回显；浏览器同时匹配请求号和 turnId，用同一个 `performance.now()` 计时：

| 事件 | 定义 |
| --- | --- |
| 首收音频 | 第一次可接受的音频事件到达 |
| `scheduled_pcm` 起播 | AudioContext 为 running、BufferSource 成功调用 `start(0)` |
| `html_playing` 起播 | HTMLAudioElement 的 `playing` 事件 |

两种起播模式分开汇总，均不声称等同于设备出声时间。服务端验证当前轮次、请求号、首音频已经发出、非取消/失败、一次性消费和 `0 ≤ 首收 ≤ 起播 ≤ 120000ms`。ID 不作为指标标签；开场、旧客户端、未匹配请求及缺失报告不补造样本。

Prometheus 名称、固定标签、采样分母与限制见根目录 `METRICS_CONTRACT.md`。语音看板增加按模式的首收/起播直方图及有效样本数；直方图分位数只用于观察，逐轮 JSON/日志才用于配对实验。未部署该看板到真实监控服务器，也未增加基于少量自报样本的告警阈值。

## 证据

- `candidate-20261001/summary.json`：验证结果、逐轮原始毫秒值、11 个源码文件指纹。
- `candidate-20261001/backend-test-results/`：fresh run 的全部测试 XML。
- `candidate-20261001/frozen-browser-protocol.jsonl`：采集时冻结的协议记录，哈希与 summary 一致。
- `runs/browser-20261001-correct-protocol.jsonl`：完整运行及关闭记录。
- `runs/browser-latest.png`：最新页面验收截图。
- `runs/browser-20261001.jsonl`：初次替身解析层级错误的诊断记录。替身开始把参数误读为顶层字段，未回显请求号；修正读取 `data` 后重跑。此文件不纳入有效报告统计。
- `baseline/`：实施前关键文件快照；本轮没有以受控音调延迟做 A/B 性能比较。

临时浏览器、Vite 与受控服务器均已关闭，未停止其他项目服务。

## 复跑受控页面

从项目根目录运行，结果路径每次使用新名称：

```powershell
python observability/experiments/client-playback/controlled_server.py --output observability/experiments/client-playback/runs/browser-new-run.jsonl
```

另一个终端：

```powershell
cd frontend
$env:VITE_API_PROXY_TARGET='http://127.0.0.1:18087'
pnpm exec vite --host 127.0.0.1 --port 5187 --strictPort
```

打开 `http://127.0.0.1:5187/interviews`，点击受控会话的“继续面试”。识别文本由替身提供，直接点“提交回答”，无需开启麦克风。依次核对 PCM、完整 WAV、等待回复后主动停止、失败、恢复后的正常轮次；再检查协议记录。该流程始终属于受控浏览器验收。

后续真实实验保持供应商/模型/音色/音频格式/问题集一致，记录提交、取消、失败、缺失与起播的所有轮次。当前真实 TTS 首次请求仍受 `AllocationQuota.FreeTierOnly` 限制；等待可用同模型凭据后复跑。
