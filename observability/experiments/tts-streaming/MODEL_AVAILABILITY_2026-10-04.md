# TTS 模型额度与可用性复验

日期：2026-10-04。用户提供的控制台截图显示 3.1 系列有剩余额度；历史失败请求和应用配置仍使用 3.0。使用原有本机凭据、北京接口及 SDK 2.22.7，实际发起共三次短文本请求，没有调整账号计费设置。

> 本文保留该批 TTS 调用当时的状态。随后已恢复隔离应用、验证新的 Chat 型号并建立 active 持续目标；当前状态见[环境恢复与模型预检](../runtime/RESUME_AND_MODEL_PREFLIGHT_2026-10-04.md)。

## 1. 真实调用结果

| 模型 / 音色 | 模式 | 结果 | PCM 字节 / 首次暴露给调用方的时间 |
| --- | --- | --- | --- |
| qwen-audio-3.0-tts-flash / longanhuan_v3.6 | 整段 | AllocationQuota.FreeTierOnly，无有效音频 | 0 / 不计入耗时 |
| qwen-audio-3.1-tts-flash / longanhuan_v3.1 | 整段 | 成功 | 272640 / 1066.591ms |
| qwen-audio-3.1-tts-flash / longanhuan_v3.1 | 帧流 | 成功，暴露 11 帧 | 272640 / 448.7261ms |

两次 3.1 请求输入相同，均为 PCM s16le 单声道 24000Hz，按字节计算各 5680ms，连接均正常关闭。未取得供应商实际 Token usage，不能以返回字节替代扣费量。

- [3.0 原始结果](runs/20261004-203212/results.json) 与 [错误诊断日志](runs/20261004-203212/execution.log)。该批旧失败退出仍为 0，不代表调用成功；脱敏正则曾误伤 task-failed 的文字标签，error_code 未被改变，日志原样保留。随后修复正则并增加失败退出码。
- [3.1 原始结果](runs/20261004-203423/results.json)、[执行日志](runs/20261004-203423/execution.log)、[本批源码指纹](runs/20261004-203423/source-manifest.json)。成功批次的三份源码已按原指纹核对并留存。

结论：原凭据可以调用 3.1 TTS，已找到继续真实语音实验的可用路径。3.0 的特定额度失败不能外推为整个账号或所有模型额度耗尽。

## 2. 已修改的配置与实验入口

- 应用 YAML 与 VoiceInterviewProperties 默认 TTS 改为 3.1 / longanhuan_v3.1；提供 APP_VOICE_TTS_MODEL、APP_VOICE_TTS_VOICE 配置入口。
- 本机 `C:\Users\yngtao\.rehevo\llm-providers.yml` 同步只替换 TTS 模型和音色，避免旧持久化配置覆盖新默认。备份为同目录 `llm-providers.yml.before-tts31-20261004`，反向替换逐字核验其他内容未变。备份没有复制进仓库。
- 实验脚本接受 Model / Voice 参数，默认采用本轮已验证组合，保存源码快照和脱敏日志；不改历史失败目录。
- 2026-10-04 全量 fresh 后端回归 `:app:test --no-daemon --rerun-tasks`：68 套件、316 测试，失败/错误/跳过均 0。它不包含真实浏览器、ASR、Chat 或模型音质验收。

本轮未启动或重启产品应用；文件更新不等于运行中的实例已重新载入。

## 3. 证据边界与接续

只有一对组件样本且顺序为整段后帧流，不能宣称稳定加速、P95 达标、音质相同或用户已听到。两份音频摘要不同，长度相同不代表波形或内容逐字相同；未保留音频原件和做听感复核。后续冻结同一 3.1 模型、同一音色与参数，用交替顺序和不同时间窗进行组件及浏览器实验。

ASR 与 Chat 的当前可用性没有在本轮验证，不能将 TTS 成功扩大为完整面试链路通过。原 S0–S4 目标尚未完成，本会话目标状态查询为空，不能声称自动目标正在续跑。

参考：[官方音色对应表](https://help.aliyun.com/zh/model-studio/qwen-audio-tts-voice-list)、[模型与地域免费额度表](https://help.aliyun.com/zh/model-studio/model-pricing)。
