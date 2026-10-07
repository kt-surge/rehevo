package interview.guide.modules.voiceinterview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** SDK 回调只入有界队列；一个消费者保持句序与帧序，失败不跳句或重播。 */
@Slf4j
public final class OrderedPcmTtsPipeline implements AutoCloseable {
  public record Frame(int sentenceIndex, int frameIndex, byte[] pcm, boolean endOfSentence) { }

  private static final class Sentence {
    final int index;
    final long deadlineNanos;
    final ArrayDeque<Frame> frames = new ArrayDeque<>();
    int nextFrame;
    boolean done;
    VoiceTtsTask task;
    Thread worker;
    Sentence(int index, long deadlineNanos) {
      this.index = index; this.deadlineNanos = deadlineNanos;
    }
  }

  private final VoiceFrameTtsClient client;
  private final Executor executor;
  private final Semaphore permits;
  private final Duration sentenceTimeout;
  private final int maxPendingSentences;
  private final int maxBufferedBytes;
  private final Consumer<Frame> output;
  private final Object monitor = new Object();
  private final List<Sentence> sentences = new ArrayList<>();
  private final CompletableFuture<Integer> completion = new CompletableFuture<>();
  private boolean finished;
  private boolean closed;
  private Throwable failure;
  private int bufferedBytes;
  private int nextSentence;
  private int nextPermitSentence;
  private int deliveredFrames;
  private Thread consumerThread;

  public OrderedPcmTtsPipeline(VoiceFrameTtsClient client, Executor executor, int concurrency,
      Duration sentenceTimeout, int maxPendingSentences, int maxBufferedBytes, Consumer<Frame> output) {
    this.client = Objects.requireNonNull(client);
    this.executor = Objects.requireNonNull(executor);
    this.output = Objects.requireNonNull(output);
    if (concurrency < 1 || maxPendingSentences < 1 || maxBufferedBytes < 2
        || sentenceTimeout == null || sentenceTimeout.isNegative() || sentenceTimeout.isZero()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "TTS 管线限制必须大于零");
    }
    this.permits = new Semaphore(concurrency, true);
    this.sentenceTimeout = sentenceTimeout;
    this.maxPendingSentences = maxPendingSentences;
    this.maxBufferedBytes = maxBufferedBytes;
    executor.execute(this::drain);
  }

  public void submit(String text) {
    if (text == null || text.isBlank()) { return; }
    Sentence sentence;
    synchronized (monitor) {
      if (closed || failure != null) { throw new CancellationException("TTS 管线已终止"); }
      if (finished) { throw new BusinessException(ErrorCode.BAD_REQUEST, "TTS 输入已结束"); }
      if (sentences.size() - nextSentence >= maxPendingSentences) {
        var error = new BusinessException(ErrorCode.AI_SERVICE_ERROR, "TTS 待处理句数超过上限");
        signalFailure(error);
        throw error;
      }
      sentence = new Sentence(sentences.size(), System.nanoTime() + sentenceTimeout.toNanos());
      sentences.add(sentence);
      monitor.notifyAll();
    }
    try { executor.execute(() -> startSentence(sentence, text)); }
    catch (Exception error) { signalFailure(error); throw error; }
  }

  private void startSentence(Sentence sentence, String text) {
    boolean acquired = false;
    boolean releaseOnCompletion = false;
    try {
      synchronized (monitor) {
        if (closed || failure != null) { return; }
        sentence.worker = Thread.currentThread();
        // 公平信号量只保证等待线程顺序，不等于 LLM 提交句序；先按句序进入许可等待。
        while (sentence.index != nextPermitSentence && !closed && failure == null) {
          long remaining = sentence.deadlineNanos - System.nanoTime();
          if (remaining <= 0) { throw new TimeoutException("TTS 句序许可等待超时"); }
          TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
        }
        if (closed || failure != null) { return; }
      }
      long remaining = sentence.deadlineNanos - System.nanoTime();
      if (remaining <= 0 || !permits.tryAcquire(remaining, TimeUnit.NANOSECONDS)) {
        throw new TimeoutException("TTS 并发许可等待超时");
      }
      acquired = true;
      synchronized (monitor) {
        if (closed || failure != null) { return; }
        nextPermitSentence++;
        monitor.notifyAll();
      }
      remaining = sentence.deadlineNanos - System.nanoTime();
      if (remaining <= 0) { throw new TimeoutException("TTS 句截止时间已到"); }
      VoiceTtsTask task = client.prepareFrames(text, Duration.ofNanos(remaining),
          pcm -> enqueue(sentence, pcm));
      boolean rejected;
      synchronized (monitor) {
        sentence.task = task;
        rejected = closed || failure != null;
      }
      // 句柄已先注册，即使现在取消，start 也不能复活任务。
      task.completion().whenComplete((value, error) -> {
        permits.release();
        synchronized (monitor) {
          sentence.done = true;
          if (!closed && error != null) { signalFailure(error); }
          monitor.notifyAll();
        }
      });
      releaseOnCompletion = true;
      if (rejected) { task.close(); } else { task.start(); }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      signalFailure(error);
    } catch (Exception error) {
      signalFailure(error);
    } finally {
      if (acquired && !releaseOnCompletion) { permits.release(); }
      synchronized (monitor) { sentence.worker = null; }
    }
  }

  private void enqueue(Sentence sentence, byte[] pcm) {
    if (pcm == null || pcm.length == 0) { return; }
    synchronized (monitor) {
      if (closed || failure != null || sentence.done) { return; }
      if (pcm.length % 2 != 0 || pcm.length > maxBufferedBytes - bufferedBytes) {
        var error = new BusinessException(ErrorCode.AI_SERVICE_ERROR,
            pcm.length % 2 != 0 ? "TTS PCM 帧未对齐" : "TTS 待播音频超过缓冲上限");
        signalFailure(error);
        throw error;
      }
      bufferedBytes += pcm.length;
      sentence.frames.addLast(new Frame(sentence.index, sentence.nextFrame++, pcm, false));
      monitor.notifyAll();
    }
  }

  private void signalFailure(Throwable error) {
    synchronized (monitor) {
      if (!closed && failure == null) { failure = error; }
      monitor.notifyAll();
    }
  }

  private void drain() {
    Throwable terminalError = null;
    try {
      synchronized (monitor) { consumerThread = Thread.currentThread(); }
      while (true) {
        Frame frame;
        synchronized (monitor) {
          while (true) {
            if (closed) { throw new CancellationException("TTS 管线已关闭"); }
            if (failure != null) { throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "TTS 帧合成失败", failure); }
            if (nextSentence == sentences.size()) {
              if (finished) { return; }
            } else {
              Sentence sentence = sentences.get(nextSentence);
              if (!sentence.frames.isEmpty()) { frame = sentence.frames.removeFirst(); break; }
              if (sentence.done) {
                frame = new Frame(sentence.index, sentence.nextFrame, new byte[0], true);
                nextSentence++;
                break;
              }
            }
            monitor.wait();
          }
        }
        output.accept(frame);
        synchronized (monitor) {
          bufferedBytes -= frame.pcm().length;
          if (!frame.endOfSentence()) { deliveredFrames++; }
        }
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      synchronized (monitor) {
        terminalError = closed ? new CancellationException("TTS 管线已关闭") : error;
      }
    } catch (Exception error) {
      terminalError = error;
    } finally {
      close();
      if (terminalError == null) { completion.complete(deliveredFrames); }
      else { completion.completeExceptionally(terminalError); }
    }
  }

  public void finish() {
    synchronized (monitor) { finished = true; monitor.notifyAll(); }
  }

  public CompletionStage<Integer> completion() { return completion.minimalCompletionStage(); }
  public int deliveredFrames() { synchronized (monitor) { return deliveredFrames; } }

  @Override
  public void close() {
    List<VoiceTtsTask> tasks;
    List<Thread> workers;
    Thread consumer;
    synchronized (monitor) {
      if (closed) { return; }
      closed = true;
      tasks = sentences.stream().map(s -> s.task).filter(Objects::nonNull).toList();
      workers = sentences.stream().map(s -> s.worker).filter(Objects::nonNull).toList();
      consumer = consumerThread;
      sentences.forEach(s -> s.frames.clear());
      monitor.notifyAll();
    }
    // 不持有队列锁关闭任务，避免 SDK 回调与清理的锁循环。
    tasks.forEach(task -> {
      try { task.close(); }
      catch (Exception error) { log.warn("TTS 句任务清理失败", error); }
    });
    workers.stream().filter(t -> t != Thread.currentThread()).forEach(Thread::interrupt);
    if (consumer != null && consumer != Thread.currentThread()) { consumer.interrupt(); }
  }
}
