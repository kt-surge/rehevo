package interview.guide.modules.voiceinterview.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("客户端起播指标输入边界")
class VoiceClientPlaybackReportTest {

  private Map<String, Object> valid() {
    return new HashMap<>(Map.of("clientRequestId", "playback-req-001", "turnId", "turn-1",
        "playbackMode", "scheduled_pcm", "submitToAudioReceivedMs", 120.5,
        "submitToPlaybackStartMs", 180.75));
  }

  @Test
  @DisplayName("接受合法同一时钟的两个阶段耗时")
  void acceptsFiniteOrderedLatencies() {
    var report = VoiceClientPlaybackReport.from(valid()).orElseThrow();
    assertThat(report.submitToAudioReceivedMs()).isEqualTo(120.5);
    assertThat(report.submitToPlaybackStartMs()).isEqualTo(180.75);
  }

  @Test
  @DisplayName("浏览器输出时间戳估计作为独立模式接受")
  void acceptsOutputTimestampMode() {
    Map<String, Object> payload = valid();
    payload.put("playbackMode", "output_pcm");
    assertThat(VoiceClientPlaybackReport.from(payload).orElseThrow().playbackMode()).isEqualTo("output_pcm");
  }

  @Test
  @DisplayName("拒绝空请求、不完整身份、未知播放模式和字符串耗时")
  void rejectsMissingOrInvalidFields() {
    assertThat(VoiceClientPlaybackReport.from(null)).isEmpty();
    for (var entry : Map.<String, Object>of("clientRequestId", "bad!",
        "turnId", " ", "playbackMode", "unknown", "submitToAudioReceivedMs", "120").entrySet()) {
      Map<String, Object> payload = valid();
      payload.put(entry.getKey(), entry.getValue());
      assertThat(VoiceClientPlaybackReport.from(payload)).isEmpty();
    }
  }

  @Test
  @DisplayName("拒绝非有限、负数、倒序和超预算耗时")
  void rejectsInvalidTimeline() {
    for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1, 120_001, 119}) {
      Map<String, Object> payload = valid();
      payload.put("submitToPlaybackStartMs", value);
      assertThat(VoiceClientPlaybackReport.from(payload)).isEmpty();
    }
    Map<String, Object> payload = valid();
    payload.put("submitToAudioReceivedMs", Double.NEGATIVE_INFINITY);
    assertThat(VoiceClientPlaybackReport.from(payload)).isEmpty();
  }
}
