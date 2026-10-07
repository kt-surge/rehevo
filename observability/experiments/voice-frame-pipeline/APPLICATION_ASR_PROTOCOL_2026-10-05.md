# 应用 ASR 受控音频验收与静音参数对照

## 当前核查与成功条件

实际 App 默认仍为 3.0 ASR；此前独立音频复查与浏览器建连使用 3.1。当前 generic RecognitionParam 只发送格式、采样率和 language_hints，未发送配置中的静音阈值。不要把 realtime 协议的 server_vad 字段当成当前 streaming 协议已经生效。

先使用已封存公开合成音频，在实际 REST 建会话 → WebSocket audio → QwenAsrService → 外部 ASR → 应用 subtitle 链路预检。不得发送 submit/end API 触发模型评估；关闭 socket 只结束自己的会话，然后保存历史、定向删除。连接自动开场可能触发一次 TTS，记录该事实和用量是否可见，不声称本批零外部调用。无真人麦克风、真实简历/JD，不属于浏览器延迟门。

先 1 份音频检查默认 3.0；失败原样保留、不自动重复试模型。再同音频、同应用源码以启动参数指定已有可用 3.1，除 model 不变；通过后扩大公开技术音频，检查文本归一化差异、ASR ready/首字幕/最后字幕时刻及上传结束后的观察。字幕事件目前未区分 ASR final/partial，不将最后字幕时间当成已证明的最后定稿时刻。

SDK 2.22.7 的 RecognitionUsage 只暴露 duration，应用未把 Token usage 暴露到客户端，本次原始 WS 不含供应商 usage。不可将未知用量补零，不能以发送音频时长估算 Token。

通过应用音频预检后：冻结当前与候选参数构建器；候选仅显式发送 max_sentence_silence，先保持可配置、不直接降低生产值。针对相同 PCM 及帧节奏随机配对，比较定稿延迟、技术词与句尾内容，再选择阈值。参数发送、输入帧、实际 SDK 回调与最终用量需进一步观测，不用客户端“没有变化”推定 final。若存在句子被过早截断或回调归属问题则暂停采用。

## 一手依据与自评审

[阿里云 Qwen Audio Streaming Java SDK](https://help.aliyun.com/en/model-studio/qwen-audio-asr-streaming-java-sdk) 描述 PCM 流、Recognition 回调及 max_sentence_silence；其阈值是 Streaming 协议参数，语义/效果以本机所用型号实测为准。保持 16kHz、s16le、单声道，100ms/3200字节节奏，公开原 24k PCM 以 scipy resample_poly 2/3 转换并冻结哈希，期望文本不发给 ASR。

由执行 Agent 自评审；同一音频预检不是整体 CER、真人听感、生产 P95 或新资料盲测。S0–S4、≥100/组及≥3窗浏览器门保持不变。
