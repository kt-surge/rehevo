package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.knowledgebase.model.RagGenerationState;
import interview.guide.modules.knowledgebase.model.RagStreamEventDTO;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RAG 完成、故障、取消的统一终态，受控流不调用模型")
class RagChatStreamServiceTest {
  private final RagChatSessionService sessions = mock(RagChatSessionService.class);
  private final RagChatStreamProperties properties = new RagChatStreamProperties();
  private RagChatStreamService service;
  private final AtomicReference<String> saved = new AtomicReference<>();
  private final AtomicReference<RagGenerationState> state = new AtomicReference<>();

  @BeforeEach
  void setup() {
    service = new RagChatStreamService(sessions, properties);
    when(sessions.prepareStreamMessage(1L, "公开问题")).thenReturn(7L);
    when(sessions.finishStreamMessage(eq(7L), anyString(), anyList(), any(), any())).thenAnswer(call -> {
      saved.set(call.getArgument(1));
      state.set(call.getArgument(3));
      return call.getArgument(3);
    });
  }

  private void source(Flux<String> source) {
    when(sessions.getStreamAnswer(1L, "公开问题"))
        .thenReturn(new KnowledgeBaseQueryService.StreamAnswer(source, List.of(), null));
  }

  private List<RagStreamEventDTO> collect() {
    var events = service.stream(1L, "公开问题").collectList().block(Duration.ofSeconds(5));
    await(() -> service.activeCount() == 0);
    assertThat(events.stream().filter(e -> e.event().equals("terminal"))).hasSize(1);
    return events;
  }

  @Test
  @DisplayName("成功先保存原始正文再发唯一终态，换行和字面反斜杠不损坏")
  void success() {
    source(Flux.just("中文\n", "字面\\n与\\r😀"));
    var events = collect();
    assertThat(events).extracting(RagStreamEventDTO::event).containsExactly("start", "delta", "delta", "terminal");
    assertThat(saved.get()).isEqualTo("中文\n字面\\n与\\r😀");
    assertThat(events.getLast().generationState()).isEqualTo(RagGenerationState.COMPLETED);
  }

  @Test
  @DisplayName("基线半途失败缺陷修复，部分内容保留且 FAILED，不把内部错误加入正文")
  void failurePreservesPrefix() {
    source(Flux.concat(Flux.just("有依据的部分"), Flux.error(new BusinessException(ErrorCode.AI_SERVICE_ERROR, "internal secret fixture"))));
    var events = collect();
    assertThat(saved.get()).isEqualTo("有依据的部分");
    assertThat(state.get()).isEqualTo(RagGenerationState.FAILED);
    assertThat(events.getLast().message()).doesNotContain("internal secret");
  }

  @Test
  @DisplayName("基线取消缺陷修复，断连保存前缀、解除上游且释放注册表")
  void disconnectPreservesPrefix() {
    var cancelled = new AtomicBoolean();
    var received = new CountDownLatch(1);
    source(Flux.concat(Flux.just("取消前的部分"), Flux.<String>never()).doOnCancel(() -> cancelled.set(true)));
    var subscription = service.stream(1L, "公开问题").subscribe(event -> {
      if (event.event().equals("delta")) received.countDown();
    });
    waitFor(received);
    subscription.dispose();
    await(() -> service.activeCount() == 0);
    assertThat(cancelled).isTrue();
    assertThat(saved.get()).isEqualTo("取消前的部分");
    assertThat(state.get()).isEqualTo(RagGenerationState.CANCELLED);
    verify(sessions, times(1)).finishStreamMessage(eq(7L), anyString(), anyList(), any(), any());
  }

  @Test
  @DisplayName("主动停止先决定取消再释放模型订阅，终态只发一次")
  void explicitCancel() {
    var cancelled = new AtomicBoolean();
    source(Flux.concat(Flux.just("前缀"), Flux.<String>never()).doOnCancel(() -> cancelled.set(true)));
    List<RagStreamEventDTO> events = new CopyOnWriteArrayList<>();
    var subscription = service.stream(1L, "公开问题").subscribe(events::add);
    await(() -> events.stream().anyMatch(e -> e.event().equals("delta")));
    assertThat(service.cancel(1L, 7L).generationState()).isEqualTo(RagGenerationState.CANCELLED);
    await(() -> service.activeCount() == 0);
    assertThat(cancelled).isTrue();
    assertThat(saved.get()).isEqualTo("前缀");
    assertThat(events.stream().filter(e -> e.event().equals("terminal"))).hasSize(1);
    assertThat(events.getLast().generationState()).isEqualTo(RagGenerationState.CANCELLED);
    subscription.dispose();
  }

  @Test
  @DisplayName("检索准备阶段取消会解除等待，迟到的回答不得订阅")
  void cancelDuringRetrieval() {
    var entered = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    when(sessions.getStreamAnswer(1L, "公开问题")).thenAnswer(call -> {
      entered.countDown();
      try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
      catch (InterruptedException error) { interrupted.set(true); Thread.currentThread().interrupt(); }
      return new KnowledgeBaseQueryService.StreamAnswer(Flux.just("迟到正文"), List.of(), null);
    });
    List<RagStreamEventDTO> events = new CopyOnWriteArrayList<>();
    service.stream(1L, "公开问题").subscribe(events::add);
    waitFor(entered);
    service.cancel(1L, 7L);
    await(() -> service.activeCount() == 0 && interrupted.get());
    assertThat(saved.get()).isEmpty();
    assertThat(events).noneMatch(e -> e.event().equals("delta"));
  }

  @Test
  @DisplayName("检索前故障也有失败终态和空正文，不遗留处理中占位")
  void disconnectDuringPreparation() {
    var entered = new CountDownLatch(1);
    var gate = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    when(sessions.prepareStreamMessage(1L, "公开问题")).thenAnswer(call -> {
      entered.countDown();
      boolean complete = false;
      while (!complete) {
        try { complete = gate.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException error) { interrupted.set(true); }
      }
      return 7L;
    });
    source(Flux.just("不该输出"));
    var subscription = service.stream(1L, "公开问题").subscribe();
    waitFor(entered); subscription.dispose(); gate.countDown();
    await(() -> service.activeCount() == 0);
    assertThat(state.get()).isEqualTo(RagGenerationState.CANCELLED);
    assertThat(saved.get()).isEmpty();
  }

  @Test
  @DisplayName("检索前故障也有失败终态和空正文，不遗留处理中占位")
  void retrievalFailure() {
    when(sessions.getStreamAnswer(1L, "公开问题")).thenThrow(new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "fixture"));
    assertThat(collect().getLast().generationState()).isEqualTo(RagGenerationState.FAILED);
    assertThat(saved.get()).isEmpty();
  }

  @Test
  @DisplayName("持久化失败不能发成功终态，保留可见正文并标明未保存")
  void saveFailure() {
    source(Flux.just("仍应保留的正文"));
    doThrow(new BusinessException(ErrorCode.INTERNAL_ERROR, "fixture write failed")).when(sessions)
        .finishStreamMessage(eq(7L), anyString(), anyList(), any(), any());
    var events = collect();
    assertThat(events.getLast().generationState()).isEqualTo(RagGenerationState.FAILED);
    assertThat(events.getLast().errorCode()).isEqualTo("SAVE_FAILED");
    assertThat(events).anyMatch(e -> "仍应保留的正文".equals(e.content()));
  }

  @Test
  @DisplayName("绝对截止时间取消永不结束的上游，保存前缀并释放资源")
  void deadline() {
    properties.setDeadlineSeconds(1);
    var cancelled = new AtomicBoolean();
    source(Flux.concat(Flux.just("前缀"), Flux.<String>never()).doOnCancel(() -> cancelled.set(true)));
    assertThat(collect().getLast().errorCode()).isEqualTo("GENERATION_TIMEOUT");
    assertThat(cancelled).isTrue();
    assertThat(saved.get()).isEqualTo("前缀");
  }

  @Test
  @DisplayName("无内容流不能宣告回答成功")
  void emptyAnswer() {
    source(Flux.empty());
    assertThat(collect().getLast().errorCode()).isEqualTo("EMPTY_ANSWER");
    assertThat(state.get()).isEqualTo(RagGenerationState.FAILED);
  }

  @Test
  @DisplayName("长度有界，越界时停止上游且保留合法前缀")
  void lengthBound() {
    properties.setMaxAnswerCharacters(3);
    source(Flux.just("前三字", "越界"));
    assertThat(collect().getLast().generationState()).isEqualTo(RagGenerationState.FAILED);
    assertThat(saved.get()).isEqualTo("前三字");
  }

  @Test
  @DisplayName("重复订阅没有共享正文或终态")
  void independentSubscriptions() {
    source(Flux.just("一次"));
    var flux = service.stream(1L, "公开问题");
    flux.blockLast(Duration.ofSeconds(5));
    await(() -> service.activeCount() == 0);
    flux.blockLast(Duration.ofSeconds(5));
    await(() -> service.activeCount() == 0);
    assertThat(saved.get()).isEqualTo("一次");
    verify(sessions, times(2)).prepareStreamMessage(1L, "公开问题");
  }

  @Test
  @DisplayName("同会话并发请求被拒绝，完成后容量可复用")
  void duplicateSession() {
    source(Flux.never());
    var subscription = service.stream(1L, "公开问题").subscribe();
    await(() -> service.activeCount() == 1);
    assertThatThrownBy(() -> service.stream(1L, "公开问题").blockLast(Duration.ofSeconds(2)))
        .isInstanceOf(BusinessException.class).hasMessageContaining("已有回答");
    subscription.dispose();
    await(() -> service.activeCount() == 0);
    source(Flux.just("恢复"));
    assertThat(collect().getLast().generationState()).isEqualTo(RagGenerationState.COMPLETED);
  }

  @Test
  @DisplayName("完成与停止竞争只有一个数据库写入和客户端终态")
  void completionRace() throws Exception {
    var source = Sinks.many().unicast().<String>onBackpressureBuffer();
    source(source.asFlux());
    List<RagStreamEventDTO> events = new CopyOnWriteArrayList<>();
    service.stream(1L, "公开问题").subscribe(events::add);
    await(() -> events.stream().anyMatch(e -> e.event().equals("start")));
    source.tryEmitNext("竞争前缀");
    await(() -> events.stream().anyMatch(e -> e.event().equals("delta")));
    var gate = new CountDownLatch(1);
    when(sessions.getStreamTerminal(1L, 7L)).thenAnswer(call ->
        RagStreamEventDTO.terminal(7L, state.get(), null, null));
    Thread completion = Thread.ofVirtual().start(() -> { waitFor(gate); source.tryEmitComplete(); });
    Thread cancel = Thread.ofVirtual().start(() -> { waitFor(gate); service.cancel(1L, 7L); });
    gate.countDown();
    completion.join(); cancel.join();
    await(() -> service.activeCount() == 0);
    assertThat(events.stream().filter(e -> e.event().equals("terminal"))).hasSize(1);
    assertThat(events.getLast().generationState()).isEqualTo(state.get());
    verify(sessions, times(1)).finishStreamMessage(eq(7L), anyString(), anyList(), any(), any());
  }

  private static void waitFor(CountDownLatch latch) {
    try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
  }

  private static void await(BooleanSupplier check) {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!check.getAsBoolean() && System.nanoTime() < end) {
      try { Thread.sleep(10); }
      catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
    assertThat(check.getAsBoolean()).isTrue();
  }
}
