# 真实 TTS 帧流组件实验

当前默认使用 SDK 2.22.7、模型 `qwen-audio-3.1-tts-flash`、配套音色 `longanhuan_v3.1`，PCM 24000Hz/单声道/16bit、语速 1.0、音量 60、zh、seed 0。模型和音色可通过脚本参数显式指定；实验参数**不是已启动应用的有效配置**。2026-10-01 的历史批次使用 3.0，原记录保持不变。

- A `whole`：调用 `call`，等待完整音频后记录调用方可取音频时间。
- B `frames`：调用 `callAsFlowable`，每帧处理，记录首个非空 PCM 暴露给调用方的时间。
- 每次新建 synthesizer 并关闭连接，配对交替 AB/BA；SDK 首包延迟另存，不能与 A 的调用方可取时间混用。
- 结果保存字节量、PCM 时长、帧数、摘要、失败状态和源码指纹；不保存密钥，不保留音频原件。
- 小批量诊断不算 P95 结果；不能替代浏览器起播/间隙和真人体验验收。

在本机通过 `REHEVO_TTS_EXPERIMENT_API_KEY` 配置可用凭据；脚本还支持 `AI_BAILIAN_API_KEY` 和已有用户环境变量 `ALI-API-KEY`。不要将密钥写在命令参数或提交到仓库。

```powershell
.\observability\experiments\tts-streaming\run-experiment.ps1 -Pairs 1
# 显式指定本轮已验证的模型与配套音色
.\observability\experiments\tts-streaming\run-experiment.ps1 -Pairs 1 -Model qwen-audio-3.1-tts-flash -Voice longanhuan_v3.1
```

一次最多 10 对；任一调用失败即停止该批，先检查原始结果。当前程序失败退出码为 2，成功为 0；历史批次可能在空音频时退出 0，必须核对 JSON 状态。每批保存源码快照、指纹及脱敏执行日志。

## 2026-10-04 可用性复验

同一份现有凭据、同一北京接口：3.0 再次失败，3.1 配套音色下整段与帧流两次请求均返回有效 PCM。原先“整个账号没有额度”的概括不成立，已更新应用默认及本机保存的 TTS 模型/音色。详见 [复验结果与配置变更](MODEL_AVAILABILITY_2026-10-04.md)。

后续 A/B 两组均固定为 3.1 和同一音色；本轮仅一对组件诊断，不形成浏览器起播、P95 或音质结论。

## 2026-10-01 首次诊断

运行目录：[20261001-144033](runs/20261001-144033/results.json)。只发起 A 组第 1 次请求，结果为 0 字节/无首音频，程序立即停止，B 未执行。

SDK 返回：`AllocationQuota.FreeTierOnly`，该模型免费额度已耗尽且账号启用了仅免费调用。没有修改账号计费设置，也没有换模型绕过对照条件。**本次无有效音频，不形成任何性能结果。**

供应商原始诊断中的非敏感字段：

```json
{
  "event": "task-failed",
  "error_code": "AllocationQuota.FreeTierOnly",
  "error_message": "The free tier of the model has been exhausted. If you wish to continue access the model on a paid basis, please disable the use free tier only mode in the management console."
}
```

旧 SDK 同步 `call` 在该失败路径返回空音频而未向调用方抛出异常，因此结果文件标为 `invalid-audio`。上面错误码来自该次 SDK 输出，不是假定所有空音频都由额度引起。后续需把供应商错误事件纳入实验诊断，确保空音频与原因都可追溯。

失败结果中的 SDK 首包延迟出现负值，属于无首音频时的无效哨兵值，不能计入任何延迟统计。原始记录保留不修改。该次 [实验源码快照](runs/20261001-144033/sources/RehevoTtsExperiment.java) 与 source-manifest 一并保留。
