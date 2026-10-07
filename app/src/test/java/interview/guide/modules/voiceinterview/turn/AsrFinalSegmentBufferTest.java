package interview.guide.modules.voiceinterview.turn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ASR 定稿缓冲区故障矩阵")
class AsrFinalSegmentBufferTest {

  @Test
  @DisplayName("同连接不同句子编号的相同发言必须保留两次")
  void retainsIdenticalTextFromDifferentSentences() {
    AsrFinalSegmentBuffer buffer = new AsrFinalSegmentBuffer();
    buffer.appendFinal(new AsrTranscriptSegment("connection-a", 1L, "我负责缓存。"), 1000);
    buffer.appendFinal(new AsrTranscriptSegment("connection-a", 2L, "我负责缓存。"), 2000);
    assertThat(buffer.preview()).isEqualTo("我负责缓存。 我负责缓存。");
  }

  @Test
  @DisplayName("重连后句子编号复用不能吞掉第二次发言")
  void retainsTextAcrossRecognitionGenerations() {
    AsrFinalSegmentBuffer buffer = new AsrFinalSegmentBuffer();
    buffer.appendFinal(new AsrTranscriptSegment("connection-a", 0L, "请再说一遍。"), 1000);
    buffer.appendFinal(new AsrTranscriptSegment("connection-b", 0L, "请再说一遍。"), 5000);
    assertThat(buffer.takeAndClear()).isEqualTo("请再说一遍。 请再说一遍。");
    assertThat(buffer.startedAtOr(9999)).isEqualTo(9999);
  }

  @Test
  @DisplayName("同一句的重复或修订定稿只更新自身，不能覆盖其他句子")
  void updatesOnlyTheIdentifiedSentence() {
    AsrFinalSegmentBuffer buffer = new AsrFinalSegmentBuffer();
    buffer.appendFinal(new AsrTranscriptSegment("connection-a", 1L, "先更新数据库。"), 1000);
    buffer.appendFinal(new AsrTranscriptSegment("connection-a", 2L, "再处理缓存。"), 2000);
    buffer.appendFinal(new AsrTranscriptSegment("connection-a", 1L, "先更新数据库。"), 3000);
    buffer.appendFinal(new AsrTranscriptSegment("connection-a", 2L, "再删除缓存。"), 4000);
    assertThat(buffer.preview()).isEqualTo("先更新数据库。 再删除缓存。");
  }

  @Test
  @DisplayName("有身份的下一句 partial 只预览；已定稿句子的迟到 partial 不回退字幕")
  void previewsPartialWithoutRegressingFinal() {
    AsrFinalSegmentBuffer buffer = new AsrFinalSegmentBuffer();
    buffer.appendFinal(new AsrTranscriptSegment("connection-a", 1L, "先更新数据库。"), 1000);
    assertThat(buffer.previewWithPartial(new AsrTranscriptSegment("connection-a", 2L, "再删除缓存")))
      .isEqualTo("先更新数据库。 再删除缓存");
    assertThat(buffer.previewWithPartial(new AsrTranscriptSegment("connection-a", 1L, "先更新")))
      .isEqualTo("先更新数据库。");
    assertThat(buffer.preview()).isEqualTo("先更新数据库。");
  }

  @Test
  @DisplayName("重复 final 不会重复进入下一轮面试输入")
  void ignoresDuplicatedFinalSegment() {
    AsrFinalSegmentBuffer buffer = new AsrFinalSegmentBuffer();

    buffer.appendFinal("我负责缓存一致性。", 1000);
    buffer.appendFinal("我负责缓存一致性。", 1100);

    assertThat(buffer.preview()).isEqualTo("我负责缓存一致性。");
    assertThat(buffer.takeAndClear()).isEqualTo("我负责缓存一致性。");
  }

  @Test
  @DisplayName("扩展 final 替换旧前缀而不是拼接出重复内容")
  void replacesConfirmedPrefixWithExtendedFinal() {
    AsrFinalSegmentBuffer buffer = new AsrFinalSegmentBuffer();

    buffer.appendFinal("我负责缓存", 1000);
    buffer.appendFinal("我负责缓存一致性", 1100);

    assertThat(buffer.preview()).isEqualTo("我负责缓存一致性");
  }

  @Test
  @DisplayName("迟到 partial 只供展示，不污染已确认输入")
  void keepsLatePartialOutOfConfirmedInput() {
    AsrFinalSegmentBuffer buffer = new AsrFinalSegmentBuffer();
    buffer.appendFinal("先更新数据库。", 1000);

    assertThat(buffer.previewWithPartial("再删除缓存")).isEqualTo("先更新数据库。 再删除缓存");
    assertThat(buffer.preview()).isEqualTo("先更新数据库。");
    assertThat(buffer.takeAndClear()).isEqualTo("先更新数据库。");
  }

  @Test
  @DisplayName("取走输入后会清除起始时间，下一轮重新计时")
  void clearsTimestampBetweenUtterances() {
    AsrFinalSegmentBuffer buffer = new AsrFinalSegmentBuffer();
    buffer.appendFinal("第一轮", 1000);

    assertThat(buffer.startedAtOr(9999)).isEqualTo(1000);
    buffer.takeAndClear();
    assertThat(buffer.startedAtOr(9999)).isEqualTo(9999);
  }
}
