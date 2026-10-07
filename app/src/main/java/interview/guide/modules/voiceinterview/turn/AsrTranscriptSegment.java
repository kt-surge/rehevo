package interview.guide.modules.voiceinterview.turn;

/** 识别器生命周期与供应商句子编号共同标识一句发言；文本不是身份。 */
public record AsrTranscriptSegment(String recognitionId, Long sentenceId, String text) {
  public static AsrTranscriptSegment unidentified(String text) {
    return new AsrTranscriptSegment(null, null, text);
  }
}
