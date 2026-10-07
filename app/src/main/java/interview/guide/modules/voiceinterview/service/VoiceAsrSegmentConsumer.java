package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.turn.AsrTranscriptSegment;
import java.util.function.Consumer;

/** 保留原 String 回调兼容性；支持句子身份的提供方传递结构化事件。 */
@FunctionalInterface
public interface VoiceAsrSegmentConsumer extends Consumer<String> {
  void acceptSegment(AsrTranscriptSegment segment);

  @Override
  default void accept(String text) {
    acceptSegment(AsrTranscriptSegment.unidentified(text));
  }
}
