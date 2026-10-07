package interview.guide.modules.voiceinterview.service;

import java.time.Duration;
import java.util.function.Consumer;

/** 帧流能力单独声明，旧整段客户端仍可用于对照。 */
public interface VoiceFrameTtsClient extends VoiceTtsClient {
  /** 先登记再 start；回调为 PCM s16le/24000/mono/16bit，配置不兼容必须在发送前失败。 */
  VoiceTtsTask prepareFrames(String text, Duration timeout, Consumer<byte[]> onFrame);
}
