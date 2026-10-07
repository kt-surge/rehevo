package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.audio.asr.recognition.timestamp.Sentence;
import com.alibaba.dashscope.common.ResultCallback;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.turn.AsrTranscriptSegment;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SDK 句子身份进入应用回调")
class QwenAsrSegmentIdentityTest {
  @Test
  @DisplayName("结构化回调取得供应商句子编号与本次识别器身份")
  void forwardsSdkIdentity() throws Exception {
    VoiceInterviewProperties properties = new VoiceInterviewProperties();
    properties.getQwen().getAsr().setApiKey("test-only-key");
    AtomicReference<ResultCallback<RecognitionResult>> callback = new AtomicReference<>();
    AtomicReference<AsrTranscriptSegment> received = new AtomicReference<>();
    Recognition recognizer = mock(Recognition.class);
    CountDownLatch ready = new CountDownLatch(1);
    doAnswer(call -> {
      callback.set(call.getArgument(1));
      ready.countDown();
      return null;
    }).when(recognizer).call(any(RecognitionParam.class),
        ArgumentMatchers.<ResultCallback<RecognitionResult>>any());
    QwenAsrService service = new QwenAsrService(properties) {
      @Override Recognition createRecognizer() { return recognizer; }
    };
    try {
      service.startTranscription("identity-control", (VoiceAsrSegmentConsumer) received::set,
          ignored -> { });
      assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
      Sentence sentence = new Sentence();
      sentence.setSentenceId(42L);
      sentence.setText("我负责缓存。");
      RecognitionResult result = mock(RecognitionResult.class);
      when(result.getSentence()).thenReturn(sentence);
      when(result.isSentenceEnd()).thenReturn(true);
      callback.get().onEvent(result);
      assertThat(received.get().recognitionId()).isNotBlank();
      assertThat(received.get().sentenceId()).isEqualTo(42L);
      assertThat(received.get().text()).isEqualTo("我负责缓存。");
    } finally {
      service.stopTranscription("identity-control");
    }
  }
}
