package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import io.reactivex.Flowable;
import io.reactivex.subscribers.DisposableSubscriber;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** 每句自己的订阅、绝对截止时间和连接；不积攒完整音频，不对部分失败重播。 */
final class PcmTtsStreamTask implements VoiceTtsTask {
  private final Callable<Flowable<SpeechSynthesisResult>> source;
  private final Runnable releaseConnection;
  private final Consumer<byte[]> onFrame;
  private final long timeoutMillis;
  private final CompletableFuture<Summary> completion = new CompletableFuture<>();
  private final CompletableFuture<Summary> cleanedCompletion;
  private final AtomicBoolean started = new AtomicBoolean();
  private final Object callbackMonitor = new Object();
  private long startNanos;
  private long firstFrameNanos;
  private long bytes;
  private int frames;
  private String requestId;
  private Integer reportedCharacters;

  private final DisposableSubscriber<SpeechSynthesisResult> subscriber = new DisposableSubscriber<>() {
    @Override
    public void onNext(SpeechSynthesisResult value) {
      try {
        accept(value);
      } catch (Exception error) {
        completion.completeExceptionally(error);
      }
    }

    @Override
    public void onError(Throwable error) {
      completion.completeExceptionally(error);
    }

    @Override
    public void onComplete() {
      synchronized (callbackMonitor) {
        if (bytes == 0) {
          completion.completeExceptionally(new BusinessException(ErrorCode.AI_SERVICE_ERROR,
              "TTS 未返回有效 PCM 音频"));
        } else {
          completion.complete(new Summary(bytes, frames, firstFrameNanos - startNanos,
              requestId, reportedCharacters));
        }
      }
    }
  };

  PcmTtsStreamTask(Callable<Flowable<SpeechSynthesisResult>> source, Runnable releaseConnection,
                   Duration timeout, Consumer<byte[]> onFrame) {
    this.source = Objects.requireNonNull(source, "source");
    this.releaseConnection = Objects.requireNonNull(releaseConnection, "releaseConnection");
    this.onFrame = Objects.requireNonNull(onFrame, "onFrame");
    if (timeout == null || timeout.isNegative() || timeout.isZero() || timeout.toMillis() == 0) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "TTS 截止时间必须大于零");
    }
    this.timeoutMillis = timeout.toMillis();
    cleanedCompletion = completion.whenComplete((result, error) -> {
      subscriber.dispose();
      releaseConnection.run();
    });
  }

  @Override
  public void start() {
    if (!started.compareAndSet(false, true) || completion.isDone()) {
      return;
    }
    startNanos = System.nanoTime();
    // 在副本上计时，超时与帧回调通过同一锁终结；每帧不重置绝对截止时间。
    completion.copy().orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenComplete((value, error) -> {
      if (error instanceof TimeoutException) {
        synchronized (callbackMonitor) {
          completion.completeExceptionally(error);
        }
      }
    });
    try {
      Flowable<SpeechSynthesisResult> stream = source.call();
      if (!completion.isDone()) {
        stream.subscribeWith(subscriber);
      }
    } catch (Exception error) {
      completion.completeExceptionally(error);
    } finally {
      // 建连期间可能被取消；SDK 返回后再关一次，覆盖迟到的连接创建。
      if (completion.isDone()) {
        releaseConnection.run();
      }
    }
  }

  private void accept(SpeechSynthesisResult result) {
    synchronized (callbackMonitor) {
      if (completion.isDone()) {
        return;
      }
      if (result.getRequestId() != null) {
        requestId = result.getRequestId();
      }
      if (result.getUsage() != null && result.getUsage().getCharacters() != null) {
        // SDK 字段是累计计费字符数，不累加每帧、不冒充 3.1 的 Token usage。
        reportedCharacters = result.getUsage().getCharacters();
      }
      byte[] pcm = QwenTtsService.copyAudio(result.getAudioFrame());
      if (pcm.length == 0) {
        return;
      }
      if (pcm.length % 2 != 0) {
        throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "TTS PCM 帧未按 16 位样本对齐");
      }
      if (firstFrameNanos == 0) {
        firstFrameNanos = System.nanoTime();
      }
      onFrame.accept(pcm);
      bytes += pcm.length;
      frames++;
    }
  }

  @Override
  public CompletionStage<Summary> completion() {
    return cleanedCompletion.minimalCompletionStage();
  }

  @Override
  public void close() {
    synchronized (callbackMonitor) {
      completion.cancel(false);
    }
  }
}
