package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisUsage;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import io.reactivex.Flowable;
import io.reactivex.processors.PublishProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PCM 帧流任务的取消、失败和绝对截止时间")
class PcmTtsStreamTaskTest {
  private static SpeechSynthesisResult frame(int... bytes) {
    byte[] data = new byte[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      data[i] = (byte) bytes[i];
    }
    var value = new SpeechSynthesisResult();
    value.setAudioFrame(ByteBuffer.wrap(data));
    return value;
  }

  @Test
  @DisplayName("准备不发起请求，空帧不计首帧，保留原样字节及累计计费字符")
  void preservesFramesAndProviderMetadata() throws Exception {
    var calls = new AtomicInteger();
    var releases = new AtomicInteger();
    List<byte[]> received = new ArrayList<>();
    var usage = new SpeechSynthesisResult();
    usage.setRequestId("controlled-request");
    usage.setUsage(SpeechSynthesisUsage.builder().characters(7).build());
    var task = new PcmTtsStreamTask(() -> {
      calls.incrementAndGet();
      return Flowable.just(frame(), frame(1, 2), usage, frame(3, 4), usage);
    }, releases::incrementAndGet, Duration.ofSeconds(2), received::add);
    assertThat(calls.get()).isZero();
    task.start();
    task.start();
    var result = task.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(received).hasSize(2);
    assertThat(received.get(0)).containsExactly(1, 2);
    assertThat(received.get(1)).containsExactly(3, 4);
    assertThat(result.bytes()).isEqualTo(4);
    assertThat(result.frames()).isEqualTo(2);
    assertThat(result.firstFrameLatencyNanos()).isPositive();
    assertThat(result.providerReportedCharacters()).isEqualTo(7);
    assertThat(result.providerRequestId()).isEqualTo("controlled-request");
    assertThat(releases.get()).isPositive();
  }

  @Test
  @DisplayName("注册后启动前取消不调用供应商，重复关闭不重复终结")
  void cancelBeforeStartPreventsCall() {
    var calls = new AtomicInteger();
    var releases = new AtomicInteger();
    var task = new PcmTtsStreamTask(() -> {
      calls.incrementAndGet();
      return Flowable.never();
    }, releases::incrementAndGet, Duration.ofSeconds(2), frame -> {});
    task.close();
    task.close();
    task.start();
    assertThat(calls.get()).isZero();
    assertThat(releases.get()).isEqualTo(1);
    assertThatThrownBy(() -> task.completion().toCompletableFuture().join())
        .hasCauseInstanceOf(CancellationException.class);
  }

  @Test
  @DisplayName("部分播出后供应商失败不重试或重播，断开订阅并释放连接")
  void partialFailureDoesNotReplay() {
    var calls = new AtomicInteger();
    var releases = new AtomicInteger();
    List<byte[]> received = new ArrayList<>();
    var failure = new BusinessException(ErrorCode.AI_SERVICE_ERROR, "受控供应商失败");
    var task = new PcmTtsStreamTask(() -> {
      calls.incrementAndGet();
      return Flowable.just(frame(1, 2)).concatWith(Flowable.error(failure));
    }, releases::incrementAndGet, Duration.ofSeconds(2), received::add);
    task.start();
    assertThatThrownBy(() -> task.completion().toCompletableFuture().join()).hasCause(failure);
    assertThat(received).hasSize(1);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(releases.get()).isPositive();
  }

  @Test
  @DisplayName("空输出和奇数字节帧明确失败，不能计为有效音频")
  void rejectsEmptyAndUnalignedAudio() {
    for (var stream : List.of(Flowable.just(frame()), Flowable.just(frame(1)))) {
      var releases = new AtomicInteger();
      List<byte[]> received = new ArrayList<>();
      var task = new PcmTtsStreamTask(() -> stream, releases::incrementAndGet,
          Duration.ofSeconds(2), received::add);
      task.start();
      assertThatThrownBy(() -> task.completion().toCompletableFuture().join())
          .hasCauseInstanceOf(BusinessException.class);
      assertThat(received).isEmpty();
      assertThat(releases.get()).isPositive();
    }
  }

  @Test
  @DisplayName("持续有帧也不延长绝对期限，超时后新帧不再下发")
  void deadlineDoesNotResetOnFrames() throws Exception {
    var source = PublishProcessor.<SpeechSynthesisResult>create();
    var releases = new AtomicInteger();
    List<byte[]> received = new ArrayList<>();
    var task = new PcmTtsStreamTask(() -> source, releases::incrementAndGet,
        Duration.ofMillis(150), received::add);
    task.start();
    source.onNext(frame(1, 2));
    Thread.sleep(60);
    source.onNext(frame(3, 4));
    assertThatThrownBy(() -> task.completion().toCompletableFuture().get(1, TimeUnit.SECONDS))
        .hasCauseInstanceOf(TimeoutException.class);
    int before = received.size();
    source.onNext(frame(5, 6));
    assertThat(received).hasSize(before);
    assertThat(source.hasSubscribers()).isFalse();
    assertThat(releases.get()).isPositive();
  }

  @Test
  @DisplayName("开始后的显式取消断开订阅，供应商迟到帧不会送给调用方")
  void activeCancellationSuppressesLateFrames() {
    var source = PublishProcessor.<SpeechSynthesisResult>create();
    List<byte[]> received = new ArrayList<>();
    var task = new PcmTtsStreamTask(() -> source, () -> {}, Duration.ofSeconds(2), received::add);
    task.start();
    source.onNext(frame(1, 2));
    task.close();
    source.onNext(frame(3, 4));
    source.onComplete();
    assertThat(received).hasSize(1);
    assertThat(source.hasSubscribers()).isFalse();
  }

  @Test
  @DisplayName("启动阻塞期间取消，迟到返回的流不会被订阅并补做连接关闭")
  void cancellationDuringStartCleansLateConnection() throws Exception {
    var entered = new CountDownLatch(1);
    var resume = new CountDownLatch(1);
    var releases = new AtomicInteger();
    var source = PublishProcessor.<SpeechSynthesisResult>create();
    var task = new PcmTtsStreamTask(() -> {
      entered.countDown();
      try {
        if (!resume.await(1, TimeUnit.SECONDS)) {
          throw new AssertionError("受控启动未释放");
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError(error);
      }
      return source;
    }, releases::incrementAndGet, Duration.ofSeconds(2), frame -> {});
    Thread worker = Thread.startVirtualThread(task::start);
    try {
      assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
      task.close();
      resume.countDown();
      worker.join(1000);
      assertThat(worker.isAlive()).isFalse();
      assertThat(source.hasSubscribers()).isFalse();
      assertThat(releases.get()).isGreaterThanOrEqualTo(2);
    } finally {
      resume.countDown();
      worker.join(1000);
    }
  }

  @Test
  @DisplayName("下游处理失败会停止订阅并释放连接，不能静默丢音频")
  void downstreamFailureTerminatesStream() {
    var releases = new AtomicInteger();
    var failure = new BusinessException(ErrorCode.AI_SERVICE_ERROR, "受控播放队列已满");
    var task = new PcmTtsStreamTask(() -> Flowable.just(frame(1, 2), frame(3, 4)),
        releases::incrementAndGet, Duration.ofSeconds(2), frame -> { throw failure; });
    task.start();
    assertThatThrownBy(() -> task.completion().toCompletableFuture().join()).hasCause(failure);
    assertThat(releases.get()).isPositive();
  }
}
