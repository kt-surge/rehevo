package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@DisplayName("ASR Streaming 静音阈值接线")
class QwenAsrSegmentationTest {
  @ParameterizedTest
  @ValueSource(ints = {199, 6001})
  @DisplayName("阈值超出协议范围时拒绝配置且不创建外部识别器")
  void rejectsInvalidThresholdBeforeProviderCall(int silenceMs) {
    VoiceInterviewProperties properties = new VoiceInterviewProperties();
    properties.getQwen().getAsr().setApiKey("test-only-key");
    properties.getQwen().getAsr().setTurnDetectionSilenceDurationMs(silenceMs);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    QwenAsrService service = new QwenAsrService(properties) {
      @Override Recognition createRecognizer() {
        throw new AssertionError("Invalid configuration must not create a provider recognizer");
      }
    };
    assertThatThrownBy(() -> service.startTranscription("invalid-threshold", ignored -> { },
        failure::set)).isInstanceOf(BusinessException.class).isSameAs(failure.get());
    assertThat(failure.get()).isInstanceOf(BusinessException.class);
    assertThat(service.hasActiveSession("invalid-threshold")).isFalse();
  }

  @ParameterizedTest
  @ValueSource(ints = {800, 2000})
  @DisplayName("配置的静音阈值必须通过 Streaming 参数送入实际 SDK")
  void forwardsConfiguredSilenceToSdk(int silenceMs) throws Exception {
    VoiceInterviewProperties properties = new VoiceInterviewProperties();
    properties.getQwen().getAsr().setApiKey("test-only-key");
    properties.getQwen().getAsr().setTurnDetectionSilenceDurationMs(silenceMs);
    AtomicReference<RecognitionParam> actual = new AtomicReference<>();
    Recognition recognizer = mock(Recognition.class);
    CountDownLatch called = new CountDownLatch(1);
    doAnswer(invocation -> {
      actual.set(invocation.getArgument(0));
      called.countDown();
      return null;
    }).when(recognizer).call(any(RecognitionParam.class),
        ArgumentMatchers.<ResultCallback<RecognitionResult>>any());
    QwenAsrService service = new QwenAsrService(properties) {
      @Override Recognition createRecognizer() { return recognizer; }
    };
    try {
      service.startTranscription("segmentation-control", ignored -> { }, ignored -> { });
      assertThat(called.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(actual.get().getParameters()).containsEntry("max_sentence_silence", silenceMs);
      assertThat(actual.get().getParameters()).doesNotContainKeys("turn_detection", "silence_duration_ms");
    } finally {
      service.stopTranscription("segmentation-control");
    }
  }
}
