package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.alibaba.dashscope.common.ResultCallback;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("ASR 重连后的旧回调隔离")
class QwenAsrCallbackFenceTest {
  @Test
  @DisplayName("旧连接迟到错误、结束和识别事件不能影响新连接")
  void fencesStaleRecognizerCallbacks() throws Exception {
    List<ResultCallback<RecognitionResult>> callbacks = new CopyOnWriteArrayList<>();
    List<Recognition> recognizers = new ArrayList<>();
    for (int index = 0; index < 2; index++) {
      Recognition recognizer = mock(Recognition.class);
      doAnswer(call -> {
        callbacks.add(call.getArgument(1));
        return null;
      }).when(recognizer).call(any(RecognitionParam.class),
          ArgumentMatchers.<ResultCallback<RecognitionResult>>any());
      recognizers.add(recognizer);
    }
    VoiceInterviewProperties properties = new VoiceInterviewProperties();
    properties.getQwen().getAsr().setApiKey("test-only-key");
    AtomicInteger factoryIndex = new AtomicInteger();
    QwenAsrService service = new QwenAsrService(properties) {
      @Override Recognition createRecognizer() { return recognizers.get(factoryIndex.getAndIncrement()); }
    };
    List<Throwable> failures = new CopyOnWriteArrayList<>();
    CountDownLatch firstReady = new CountDownLatch(1);
    service.startTranscription("controlled", ignored -> { }, ignored -> { }, firstReady::countDown, failures::add);
    assertThat(firstReady.await(2, TimeUnit.SECONDS)).isTrue();
    service.stopTranscription("controlled");
    CountDownLatch secondReady = new CountDownLatch(1);
    service.startTranscription("controlled", ignored -> { }, ignored -> { }, secondReady::countDown, failures::add);
    assertThat(secondReady.await(2, TimeUnit.SECONDS)).isTrue();

    callbacks.get(0).onError(new IllegalStateException("late old error"));
    callbacks.get(0).onComplete();
    RecognitionResult late = mock(RecognitionResult.class);
    callbacks.get(0).onEvent(late);
    assertThat(failures).isEmpty();
    assertThat(service.isReady("controlled")).isTrue();
    verifyNoInteractions(late);

    callbacks.get(1).onError(new IllegalStateException("current error"));
    callbacks.get(1).onError(new IllegalStateException("duplicate error"));
    assertThat(failures).hasSize(1);
    assertThat(service.isReady("controlled")).isFalse();
    service.stopTranscription("controlled");
  }
}
