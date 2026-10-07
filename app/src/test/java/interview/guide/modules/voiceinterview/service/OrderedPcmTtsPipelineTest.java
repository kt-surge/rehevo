package interview.guide.modules.voiceinterview.service;

import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("有界按句序 PCM 帧管线")
class OrderedPcmTtsPipelineTest {
  private final List<OrderedPcmTtsPipeline> pipelines = new ArrayList<>();

  @AfterEach void cleanup() { pipelines.forEach(OrderedPcmTtsPipeline::close); }

  @Test
  @DisplayName("首句立即发首帧，后句先完成也不插播，句末只有一个")
  void preservesSentenceAndFrameOrder() throws Exception {
    var client = new Client();
    var output = new CopyOnWriteArrayList<OrderedPcmTtsPipeline.Frame>();
    var pipeline = pipeline(client, 2, 1024, Duration.ofSeconds(2), output::add);
    pipeline.submit("a"); pipeline.submit("b"); pipeline.finish();
    Task a = client.await("a"); Task b = client.await("b");
    a.emit(new byte[] {1, 2});
    awaitCount(output, 1);
    b.emit(new byte[] {5, 6}); b.emit(new byte[] {7, 8}); b.succeed();
    assertThat(output).allMatch(frame -> frame.sentenceIndex() == 0);
    a.emit(new byte[] {3, 4}); a.succeed();
    assertThat(pipeline.completion().toCompletableFuture().get(2, TimeUnit.SECONDS)).isEqualTo(4);
    assertThat(output.stream().map(f -> f.sentenceIndex() + ":" + f.frameIndex() + ":" + f.endOfSentence()))
        .containsExactly("0:0:false", "0:1:false", "0:2:true", "1:0:false", "1:1:false", "1:2:true");
  }

  @Test
  @DisplayName("后句缓冲溢出明确失败并关闭所有任务，不静默丢帧")
  void failsOnOverflow() throws Exception {
    var client = new Client();
    var output = new CopyOnWriteArrayList<OrderedPcmTtsPipeline.Frame>();
    var pipeline = pipeline(client, 2, 8, Duration.ofSeconds(2), output::add);
    pipeline.submit("a"); pipeline.submit("b"); pipeline.finish();
    Task a = client.await("a"); Task b = client.await("b");
    assertThatThrownBy(() -> b.emit(new byte[10])).hasMessageContaining("缓冲上限");
    assertThatThrownBy(() -> pipeline.completion().toCompletableFuture().get(2, TimeUnit.SECONDS))
        .hasCauseInstanceOf(BusinessException.class);
    assertThat(a.closed).isTrue(); assertThat(b.closed).isTrue();
    assertThat(output).isEmpty();
  }

  @Test
  @DisplayName("正在阻塞写出的帧仍占字节预算")
  void includesInflightWriteInBudget() throws Exception {
    var client = new Client();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var pipeline = pipeline(client, 2, 8, Duration.ofSeconds(2), frame -> {
      entered.countDown();
      try { release.await(); }
      catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    });
    pipeline.submit("a"); pipeline.submit("b"); pipeline.finish();
    Task a = client.await("a"); Task b = client.await("b");
    a.emit(new byte[6]);
    assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
    try { assertThatThrownBy(() -> b.emit(new byte[6])).hasMessageContaining("缓冲上限"); }
    finally { release.countDown(); }
    assertThatThrownBy(() -> pipeline.completion().toCompletableFuture().get(2, TimeUnit.SECONDS))
        .hasCauseInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("部分播出后失败不重试、不发句末，后续句关闭")
  void failsAfterPartialAudioWithoutReplay() throws Exception {
    var client = new Client();
    var output = new CopyOnWriteArrayList<OrderedPcmTtsPipeline.Frame>();
    var pipeline = pipeline(client, 2, 1024, Duration.ofSeconds(2), output::add);
    pipeline.submit("a"); pipeline.submit("b"); pipeline.finish();
    Task a = client.await("a"); Task b = client.await("b");
    a.emit(new byte[] {1, 2}); awaitCount(output, 1);
    a.completion.completeExceptionally(new IllegalStateException("受控网络失败"));
    assertThatThrownBy(() -> pipeline.completion().toCompletableFuture().get(2, TimeUnit.SECONDS))
        .hasCauseInstanceOf(BusinessException.class);
    b.emit(new byte[] {3, 4});
    assertThat(output).hasSize(1); assertThat(output.getFirst().endOfSentence()).isFalse();
    assertThat(client.tasks).hasSize(2); assertThat(b.closed).isTrue();
  }

  @Test
  @DisplayName("取消中断许可等待，迟到回调被丢弃，待排句不建连")
  void cancelsPermitWaitAndDropsLateFrames() throws Exception {
    var client = new Client();
    var output = new CopyOnWriteArrayList<OrderedPcmTtsPipeline.Frame>();
    var pipeline = pipeline(client, 1, 1024, Duration.ofSeconds(4), output::add);
    pipeline.submit("a"); Task a = client.await("a"); pipeline.submit("b");
    pipeline.close(); a.emit(new byte[] {1, 2});
    assertThatThrownBy(() -> pipeline.completion().toCompletableFuture().get(2, TimeUnit.SECONDS))
        .hasCauseInstanceOf(CancellationException.class);
    assertThat(client.tasks).containsOnlyKeys("a"); assertThat(a.closed).isTrue();
    assertThat(output).isEmpty();
  }

  @Test
  @DisplayName("许可等待遵守句提交时的绝对截止时间")
  void boundsPermitWaitByAbsoluteDeadline() throws Exception {
    var client = new Client();
    var pipeline = pipeline(client, 1, 1024, Duration.ofMillis(150), frame -> { });
    pipeline.submit("a"); Task a = client.await("a"); pipeline.submit("b"); pipeline.finish();
    assertThatThrownBy(() -> pipeline.completion().toCompletableFuture().get(2, TimeUnit.SECONDS))
        .hasCauseInstanceOf(BusinessException.class);
    assertThat(client.tasks).containsOnlyKeys("a"); assertThat(a.closed).isTrue();
  }

  @Test
  @DisplayName("取消覆盖 prepare 尚未返回的句柄，迟到句柄先关闭再禁止启动")
  void cancelsLatePreparedTask() throws Exception {
    var preparing = new CountDownLatch(1); var release = new CountDownLatch(1);
    var client = new Client() {
      @Override public VoiceTtsTask prepareFrames(String text, Duration timeout, Consumer<byte[]> callback) {
        preparing.countDown();
        boolean interrupted = false;
        while (release.getCount() != 0) {
          try { release.await(); }
          catch (InterruptedException error) { interrupted = true; }
        }
        if (interrupted) { Thread.currentThread().interrupt(); }
        return super.prepareFrames(text, timeout, callback);
      }
    };
    var pipeline = pipeline(client, 1, 1024, Duration.ofSeconds(2), frame -> { });
    pipeline.submit("a"); assertThat(preparing.await(2, TimeUnit.SECONDS)).isTrue();
    pipeline.close(); release.countDown();
    Task task = client.awaitPrepared("a");
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!task.closed.get() && System.nanoTime() < deadline) { Thread.sleep(5); }
    assertThat(task.closed).isTrue(); assertThat(task.started.getCount()).isEqualTo(1);
  }

  private OrderedPcmTtsPipeline pipeline(Client client, int concurrency, int bytes, Duration timeout,
      Consumer<OrderedPcmTtsPipeline.Frame> output) {
    var pipeline = new OrderedPcmTtsPipeline(client, task -> Thread.ofVirtual().start(task),
        concurrency, timeout, 8, bytes, output);
    pipelines.add(pipeline); return pipeline;
  }

  @Test
  @DisplayName("后句工作线程先调度时仍由首句先占单个许可，避免首音等待后句整段合成")
  void reservesPermitInSubmissionOrder() throws Exception {
    var client = new Client();
    var heldFirstWorker = new AtomicReference<Runnable>();
    var scheduled = new AtomicInteger();
    var pipeline = new OrderedPcmTtsPipeline(client, task -> {
      if (scheduled.getAndIncrement() == 1) { heldFirstWorker.set(task); }
      else { Thread.ofVirtual().start(task); }
    }, 1, Duration.ofSeconds(2), 8, 1024, frame -> { });
    pipelines.add(pipeline);
    pipeline.submit("a"); pipeline.submit("b"); pipeline.finish();
    // 故意让 B 的工作线程提前运行；A 尚未被调度，B 不应调用供应商。
    Thread.sleep(100);
    assertThat(client.tasks).isEmpty();
    Thread.ofVirtual().start(heldFirstWorker.get());
    Task a = client.await("a");
    assertThat(client.tasks).containsOnlyKeys("a");
    a.emit(new byte[] {1, 2}); a.succeed();
    Task b = client.await("b"); b.emit(new byte[] {3, 4}); b.succeed();
    assertThat(pipeline.completion().toCompletableFuture().get(2, TimeUnit.SECONDS)).isEqualTo(2);
  }

  @Test
  @DisplayName("待处理句数超过上限立即失败，已准备的任务被释放")
  void boundsPendingSentences() throws Exception {
    var client = new Client();
    var pipeline = new OrderedPcmTtsPipeline(client, task -> Thread.ofVirtual().start(task),
        1, Duration.ofSeconds(2), 1, 1024, frame -> { });
    pipelines.add(pipeline);
    pipeline.submit("a"); Task task = client.await("a");
    assertThatThrownBy(() -> pipeline.submit("b")).hasMessageContaining("句数超过上限");
    assertThatThrownBy(() -> pipeline.completion().toCompletableFuture().get(2, TimeUnit.SECONDS))
        .hasCauseInstanceOf(BusinessException.class);
    assertThat(task.closed).isTrue(); assertThat(client.tasks).containsOnlyKeys("a");
  }

  private static void awaitCount(List<?> values, int count) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (values.size() < count && System.nanoTime() < deadline) { Thread.sleep(5); }
    assertThat(values.size()).isGreaterThanOrEqualTo(count);
  }

  private static class Client implements VoiceFrameTtsClient {
    final Map<String, Task> tasks = new ConcurrentHashMap<>();
    @Override public byte[] synthesize(String text) { throw new AssertionError("帧管线不能调用旧整段降级"); }
    @Override public VoiceTtsTask prepareFrames(String text, Duration timeout, Consumer<byte[]> callback) {
      var task = new Task(callback); tasks.put(text, task); return task;
    }
    Task awaitPrepared(String text) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (!tasks.containsKey(text) && System.nanoTime() < deadline) { Thread.sleep(5); }
      assertThat(tasks).containsKey(text); return tasks.get(text);
    }
    Task await(String text) throws Exception {
      Task task = awaitPrepared(text);
      assertThat(task.started.await(2, TimeUnit.SECONDS)).isTrue(); return task;
    }
  }

  private static final class Task implements VoiceTtsTask {
    final Consumer<byte[]> callback;
    final CompletableFuture<Summary> completion = new CompletableFuture<>();
    final CountDownLatch started = new CountDownLatch(1);
    final AtomicBoolean closed = new AtomicBoolean();
    Task(Consumer<byte[]> callback) { this.callback = callback; }
    @Override public void start() { if (!closed.get()) { started.countDown(); } }
    void emit(byte[] pcm) { callback.accept(pcm); }
    void succeed() { completion.complete(new Summary(2, 1, 0, "fake", 1)); }
    @Override public CompletionStage<Summary> completion() { return completion; }
    @Override public void close() { closed.set(true); completion.cancel(false); }
  }
}
