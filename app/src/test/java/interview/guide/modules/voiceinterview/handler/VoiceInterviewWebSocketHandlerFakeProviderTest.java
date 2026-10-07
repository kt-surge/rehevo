package interview.guide.modules.voiceinterview.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.metrics.AppMetricNames;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.service.DashscopeLlmService;
import interview.guide.modules.voiceinterview.service.VoiceAsrClient;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import interview.guide.modules.voiceinterview.service.VoiceLlmClient;
import interview.guide.modules.voiceinterview.service.VoiceTtsClient;
import interview.guide.modules.voiceinterview.service.VoiceFrameTtsClient;
import interview.guide.modules.voiceinterview.service.VoiceTtsTask;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("语音 Handler Fake Provider 故障实验")
class VoiceInterviewWebSocketHandlerFakeProviderTest {

    private final List<VoiceInterviewWebSocketHandler> handlers = new CopyOnWriteArrayList<>();

  @Test
  @DisplayName("排队中的用户轮次取消后不启动模型且不会重新播报")
  void cancelsTurnBeforeQueuedTaskStarts() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    VoiceLlmClient llm = new FakeLlm("完整的问题？") {
      @Override
      public String chat(String input, VoiceInterviewSessionEntity entity, List<String> history) {
        calls.incrementAndGet();
        return super.chat(input, entity, history);
      }
    };
    Fixture fixture = fixture(new FakeAsr(), new FakeTts(new byte[] {1, 2}, null), llm, false);
    assertThat(awaitMessage(fixture.sent, "asr_ready", Duration.ofSeconds(2))).isTrue();
    ExecutorService original = (ExecutorService) ReflectionTestUtils.getField(fixture.handler, "voicePipelineExecutor");
    original.shutdown();
    assertThat(original.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    ThreadPoolExecutor queued = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    ReflectionTestUtils.setField(fixture.handler, "voicePipelineExecutor", queued);
    CountDownLatch occupied = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch drained = new CountDownLatch(1);
    queued.execute(() -> {
      occupied.countDown();
      try { release.await(3, TimeUnit.SECONDS); }
      catch (InterruptedException error) { Thread.currentThread().interrupt(); }
    });
    assertThat(occupied.await(1, TimeUnit.SECONDS)).isTrue();
    fixture.handler.handleTextMessage(fixture.session, submit("任务版本如何比较？"));
    fixture.handler.handleTextMessage(fixture.session, cancel("queued-cancel-0001"));
    assertThat(fixture.sent).anyMatch(message -> message.contains("queued-cancel-0001"));
    queued.execute(drained::countDown);
    release.countDown();
    assertThat(drained.await(2, TimeUnit.SECONDS)).isTrue();
    assertThat(calls).hasValue(0);
    assertThat(messagesOfType(fixture.sent, "audio")).isEmpty();
    assertThat(fixture.sent).noneMatch(message -> message.contains("\"action\":\"turn_completed\""));
    assertThat(firstControlMessage(fixture.sent, "turn_cancelled").path("cancelRequestId").asText())
        .isEqualTo("queued-cancel-0001");
    fixture.handler.handleTextMessage(fixture.session, submit("继续下一题？"));
    assertThat(awaitMessage(fixture.sent, "turn_completed", Duration.ofSeconds(2))).isTrue();
    assertThat(calls).hasValue(1);
  }

  @Test
  @DisplayName("执行器拒绝提交时关闭轮次并释放处理标记，下一次提交可成功")
  void recoversAfterTaskRejection() throws Exception {
    Fixture fixture = fixture(new FakeTts(new byte[] {1, 2}, null));
    assertThat(awaitMessage(fixture.sent, "asr_ready", Duration.ofSeconds(2))).isTrue();
    ExecutorService original = (ExecutorService) ReflectionTestUtils.getField(fixture.handler, "voicePipelineExecutor");
    original.shutdown();
    assertThat(original.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    fixture.handler.handleTextMessage(fixture.session, submit("第一题？"));
    assertThat(awaitMessage(fixture.sent, "turn_failed", Duration.ofSeconds(1))).isTrue();
    ThreadPoolExecutor replacement = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>());
    ReflectionTestUtils.setField(fixture.handler, "voicePipelineExecutor", replacement);
    fixture.handler.handleTextMessage(fixture.session, submit("恢复之后的新问题？"));
    assertThat(awaitMessage(fixture.sent, "turn_completed", Duration.ofSeconds(2))).isTrue();
  }

    @AfterEach
    void tearDown() {
        handlers.forEach(VoiceInterviewWebSocketHandler::destroy);
    }

    @Test
    @DisplayName("Fake TTS 返回空音频时不发送 audio，Turn 仍可结束")
    void doesNotSendAudioWhenFakeTtsReturnsEmptyAudio() throws Exception {
        FakeTts tts = new FakeTts(new byte[0], null);
        Fixture fixture = fixture(tts);

        fixture.handler.handleTextMessage(fixture.session, submit("缓存一致性如何处理？"));

        assertThat(tts.started.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitMessage(fixture.sent, "turn_completed", Duration.ofSeconds(3))).isTrue();
        assertThat(messagesOfType(fixture.sent, "audio")).isEmpty();
    }

    @Test
    @DisplayName("Fake TTS 迟到返回时，取消后的旧音频不会写出")
    void dropsLateFakeTtsAudioAfterCancellation() throws Exception {
        CountDownLatch releaseAudio = new CountDownLatch(1);
        FakeTts tts = new FakeTts(new byte[] {1, 2, 3}, releaseAudio);
        Fixture fixture = fixture(tts);

        fixture.handler.handleTextMessage(fixture.session, submit("请解释缓存一致性。"));
        assertThat(tts.started.await(3, TimeUnit.SECONDS)).isTrue();

        fixture.handler.handleTextMessage(fixture.session, cancel());
        assertThat(awaitMessage(fixture.sent, "turn_cancelled", Duration.ofSeconds(2))).isTrue();
        releaseAudio.countDown();
        Thread.sleep(150);

        assertThat(messagesOfType(fixture.sent, "audio")).isEmpty();
    }

    @Test
    @DisplayName("Fake TTS 乱序完成时，流式音频仍按句子序号发送")
    void emitsStreamingAudioChunksInOrderWhenFakeTtsCompletesOutOfOrder() throws Exception {
        CountDownLatch releaseFirst = new CountDownLatch(1);
        OutOfOrderTts tts = new OutOfOrderTts(releaseFirst);
        Fixture fixture = fixture(new FakeAsr(), tts,
            new StreamingFakeLlm(List.of("第一句。", "第二句。")), true);

        fixture.handler.handleTextMessage(fixture.session, submit("请继续追问。"));
        assertThat(tts.firstStarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(tts.secondFinished.await(3, TimeUnit.SECONDS)).isTrue();
        releaseFirst.countDown();

        assertThat(awaitMessage(fixture.sent, "turn_completed", Duration.ofSeconds(3))).isTrue();
        assertThat(audioChunkIndexes(fixture.sent)).containsExactly(0, 1);
        assertThat(outboundTurnSequences(fixture.sent)).isSorted().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("Fake ASR append 断连后会重连并重投当前音频帧")
    void reconnectsAndRetriesAudioWhenFakeAsrAppendFails() throws Exception {
        RecoveringAsr asr = new RecoveringAsr();
        Fixture fixture = fixture(asr, new FakeTts(new byte[] {1}, null),
            new FakeLlm("无需触发 LLM"), false);

        fixture.handler.handleTextMessage(fixture.session,
            new TextMessage("{\"type\":\"audio\",\"data\":\"AQID\"}"));

        assertThat(asr.restarted.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(asr.retriedAudio.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.sent).noneMatch(message -> message.contains("\"type\":\"error\""));
    }

    @Test
    @DisplayName("Fake LLM 迟到 token 与 sentence 在取消后均不会写出或触发 TTS")
    void dropsLateFakeLlmCallbacksAfterCancellation() throws Exception {
        DelayedStreamingLlm llm = new DelayedStreamingLlm();
        FakeTts tts = new FakeTts(new byte[] {1}, null);
        Fixture fixture = fixture(new FakeAsr(), tts, llm, true);

        fixture.handler.handleTextMessage(fixture.session, submit("请继续。"));
        assertThat(llm.started.await(3, TimeUnit.SECONDS)).isTrue();
        fixture.handler.handleTextMessage(fixture.session, cancel());
        assertThat(awaitMessage(fixture.sent, "turn_cancelled", Duration.ofSeconds(2))).isTrue();

        llm.release.countDown();
        assertThat(llm.finished.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);

        assertThat(fixture.sent).noneMatch(message -> message.contains("迟到回复"));
        assertThat(messagesOfType(fixture.sent, "audio_chunk")).isEmpty();
        assertThat(tts.started.getCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Fake TTS 超时后 Turn 可结束且不发送半成品音频")
    void completesTurnWithoutAudioWhenFakeTtsTimesOut() throws Exception {
        TimeoutThenEmptyTts tts = new TimeoutThenEmptyTts();
        Fixture fixture = fixture(new FakeAsr(), tts,
            new StreamingFakeLlm(List.of("只有一句。")), true, 1);

        fixture.handler.handleTextMessage(fixture.session, submit("请继续。"));
        assertThat(tts.firstStarted.await(3, TimeUnit.SECONDS)).isTrue();

        assertThat(awaitMessage(fixture.sent, "turn_completed", Duration.ofSeconds(8))).isTrue();
        assertThat(messagesOfType(fixture.sent, "audio_chunk")).isEmpty();
        assertThat(messagesOfType(fixture.sent, "audio")).isEmpty();
    }

    @Test
    @DisplayName("20 次 Fake LLM 取消均返回带 Turn 元数据的确认，P95 小于 200ms")
    void acknowledgesTwentyControlledCancellationsWithinP95Budget() throws Exception {
        List<Long> acknowledgementMillis = new ArrayList<>();

        for (int attempt = 0; attempt < 20; attempt++) {
            DelayedStreamingLlm llm = new DelayedStreamingLlm();
            Fixture fixture = fixture(new FakeAsr(), new FakeTts(new byte[] {1}, null), llm, true);
            fixture.handler.handleTextMessage(fixture.session, submit("请继续追问。"));
            assertThat(llm.started.await(3, TimeUnit.SECONDS)).isTrue();

            long startedAt = System.nanoTime();
            fixture.handler.handleTextMessage(fixture.session, cancel());
            assertThat(awaitMessage(fixture.sent, "turn_cancelled", Duration.ofSeconds(2))).isTrue();
            acknowledgementMillis.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));

            JsonNode acknowledgement = firstControlMessage(fixture.sent, "turn_cancelled");
            assertThat(acknowledgement.path("turnId").asText()).isEqualTo("turn-1");
            assertThat(acknowledgement.path("eventId").asText()).matches("turn-1-event-\\d+");
            assertThat(acknowledgement.path("sequence").asLong()).isPositive();
            assertThat(acknowledgement.path("turnPhase").asText()).isEqualTo("CANCELLED");

            llm.release.countDown();
            assertThat(llm.finished.await(3, TimeUnit.SECONDS)).isTrue();
        }

        Collections.sort(acknowledgementMillis);
        long p95 = acknowledgementMillis.get((int) Math.ceil(acknowledgementMillis.size() * 0.95) - 1);
        assertThat(p95).as("受控 Fake Provider 的 cancel ack P95 (ms)").isLessThan(200);
    }

    @Test
    @DisplayName("仅匹配当前取消请求号的浏览器确认会记录一次客户端往返时延")
    void recordsOneClientAcknowledgementForMatchingCancelRequest() throws Exception {
        DelayedStreamingLlm llm = new DelayedStreamingLlm();
        Fixture fixture = fixture(new FakeAsr(), new FakeTts(new byte[] {1}, null), llm, true);

        fixture.handler.handleTextMessage(fixture.session, submit("请继续追问。"));
        assertThat(llm.started.await(3, TimeUnit.SECONDS)).isTrue();
        fixture.handler.handleTextMessage(fixture.session, cancel("client-cancel-0001"));
        assertThat(awaitMessage(fixture.sent, "turn_cancelled", Duration.ofSeconds(2))).isTrue();
        assertThat(firstControlMessage(fixture.sent, "turn_cancelled").path("cancelRequestId").asText())
            .isEqualTo("client-cancel-0001");

        fixture.handler.handleTextMessage(fixture.session, cancelAcknowledged("client-cancel-0001", 37));
        fixture.handler.handleTextMessage(fixture.session, cancelAcknowledged("client-cancel-0001", 37));

        assertThat(fixture.sent).noneMatch(message -> message.contains("控制消息处理失败"));
        assertThat(fixture.meterRegistry.get(AppMetricNames.VOICE_TURN_CLIENT_CANCEL_ACK)
            .tag(AppMetricNames.TAG_STATUS, "success").timer().count()).isEqualTo(1);
        llm.release.countDown();
        assertThat(llm.finished.await(3, TimeUnit.SECONDS)).isTrue();
    }

  @Test
  @DisplayName("服务端已生成完成时仍确认播放取消，保留完成终态并只记录一次关联往返时间")
  void confirmsPlaybackCancellationAfterGenerationCompleted() throws Exception {
    Fixture fixture = fixture(new FakeTts(new byte[] {1, 2}, null));
    fixture.handler.handleTextMessage(fixture.session, submit("请继续追问。"));
    assertThat(awaitMessage(fixture.sent, "turn_completed", Duration.ofSeconds(3))).isTrue();

    fixture.handler.handleTextMessage(fixture.session, cancel("completed-cancel-0001"));
    assertThat(awaitMessage(fixture.sent, "cancel_confirmed", Duration.ofSeconds(2))).isTrue();
    JsonNode confirmation = firstControlMessage(fixture.sent, "cancel_confirmed");
    assertThat(confirmation.path("cancelRequestId").asText()).isEqualTo("completed-cancel-0001");
    assertThat(confirmation.path("cancelOutcome").asText()).isEqualTo("already_terminal");
    assertThat(firstControlMessage(fixture.sent, "turn_completed").path("turnPhase").asText())
        .isEqualTo("COMPLETED");
    assertThat(fixture.sent).noneMatch(message -> message.contains("\"action\":\"turn_cancelled\""));

    fixture.handler.handleTextMessage(fixture.session, cancelAcknowledged("completed-cancel-0001", 29));
    fixture.handler.handleTextMessage(fixture.session, cancelAcknowledged("completed-cancel-0001", 29));
    assertThat(fixture.meterRegistry.get(AppMetricNames.VOICE_TURN_CLIENT_CANCEL_ACK)
        .tag(AppMetricNames.TAG_STATUS, "success").timer().count()).isEqualTo(1);
  }

    @Test
    @DisplayName("开场题也有可取消 Turn，取消后不发送迟到音频且记录浏览器确认")
    void cancelsOpeningTurnAndRecordsMatchingBrowserAcknowledgement() throws Exception {
        CountDownLatch releaseOpeningAudio = new CountDownLatch(1);
        FakeTts tts = new FakeTts(new byte[] {1, 2, 3}, releaseOpeningAudio);
        Fixture fixture = fixture(new FakeAsr(), tts, new FakeLlm("无需触发 LLM"), false, 8, false);

        assertThat(tts.started.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitMessage(fixture.sent, "turn_started", Duration.ofSeconds(2))).isTrue();
        JsonNode started = firstControlMessage(fixture.sent, "turn_started");
        assertThat(started.path("turnId").asText()).isEqualTo("turn-1");

        fixture.handler.handleTextMessage(fixture.session, cancel("opening-cancel-0001"));
        assertThat(awaitMessage(fixture.sent, "turn_cancelled", Duration.ofSeconds(2))).isTrue();
        JsonNode cancelled = firstControlMessage(fixture.sent, "turn_cancelled");
        assertThat(cancelled.path("turnId").asText()).isEqualTo("turn-1");
        assertThat(cancelled.path("cancelRequestId").asText()).isEqualTo("opening-cancel-0001");

        fixture.handler.handleTextMessage(fixture.session, cancelAcknowledged("opening-cancel-0001", 23));
        releaseOpeningAudio.countDown();
        Thread.sleep(100);

        assertThat(messagesOfType(fixture.sent, "audio")).isEmpty();
        assertThat(fixture.meterRegistry.get(AppMetricNames.VOICE_TURN_CLIENT_CANCEL_ACK)
            .tag(AppMetricNames.TAG_STATUS, "success").timer().count()).isEqualTo(1);
    }

    private Fixture fixture(FakeTts tts) throws Exception {
        return fixture(new FakeAsr(), tts, new FakeLlm("这是面试官的追问。"), false);
    }

  @Test
  @DisplayName("部分LLM流失败进入唯一失败终态，迟到TTS不发送音频")
  void failsPartialStreamWithoutLateAudio() throws Exception {
    CountDownLatch releaseAudio = new CountDownLatch(1);
    FakeTts tts = new FakeTts(new byte[] {1, 2}, releaseAudio);
    VoiceLlmClient llm = new VoiceLlmClient() {
      @Override
      public String chat(String input, VoiceInterviewSessionEntity session, List<String> history) {
        return "未使用的非流式回答";
      }

      @Override
      public DashscopeLlmService.VoiceLlmResponse chatStreamSentences(
          String input, Consumer<String> onToken, Consumer<String> onSentence,
          VoiceInterviewSessionEntity session, List<String> history) {
        onSentence.accept("已生成但尚未合成完成的第一句。");
        return new DashscopeLlmService.VoiceLlmResponse("流式生成失败", false, true);
      }
    };
    Fixture fixture = fixture(new FakeAsr(), tts, llm, true);

    fixture.handler.handleTextMessage(fixture.session, submit("请继续追问。"));
    assertThat(tts.started.await(3, TimeUnit.SECONDS)).isTrue();
    try {
      assertThat(awaitMessage(fixture.sent, "turn_failed", Duration.ofSeconds(3))).isTrue();
    } finally {
      releaseAudio.countDown();
    }
    Thread.sleep(150);

    assertThat(messagesOfType(fixture.sent, "audio_chunk")).isEmpty();
    assertThat(fixture.sent).filteredOn(message -> message.contains("\"action\":\"turn_failed\""))
        .hasSize(1);
    assertThat(fixture.sent).noneMatch(message -> message.contains("\"action\":\"turn_completed\""));
    assertThat(fixture.sent).noneMatch(message -> message.contains("\"action\":\"audio_complete\""));
  }

  @Test
  @DisplayName("起播上报绑定提交和轮次，拒绝早报、错配、重复与过期样本")
  void validatesClientPlaybackReportIdentityAndTiming() throws Exception {
    CountDownLatch releaseAudio = new CountDownLatch(1);
    FakeTts tts = new FakeTts(new byte[] {1, 2}, releaseAudio);
    Fixture fixture = fixture(new FakeAsr(), tts,
        new StreamingFakeLlm(List.of("有效的追问。")), true);
    fixture.handler.handleTextMessage(fixture.session, new TextMessage(
        "{\"type\":\"control\",\"action\":\"submit\",\"data\":{\"text\":\"请继续\","
            + "\"clientRequestId\":\"playback-req-001\"}}"));
    assertThat(tts.started.await(3, TimeUnit.SECONDS)).isTrue();
    JsonNode started = firstControlMessage(fixture.sent, "turn_started");
    String turnId = started.path("turnId").asText();
    assertThat(started.path("clientRequestId").asText()).isEqualTo("playback-req-001");
    try {
      fixture.handler.handleTextMessage(fixture.session, playbackReport("playback-req-001", turnId));
    } finally {
      releaseAudio.countDown();
    }
    assertThat(awaitMessage(fixture.sent, "turn_completed", Duration.ofSeconds(3))).isTrue();
    fixture.handler.handleTextMessage(fixture.session, playbackReport("wrong-request-01", turnId));
    fixture.handler.handleTextMessage(fixture.session, playbackReport("playback-req-001", "wrong-turn"));
    fixture.handler.handleTextMessage(fixture.session, playbackReport("playback-req-001", turnId));
    fixture.handler.handleTextMessage(fixture.session, playbackReport("playback-req-001", turnId));

    assertThat(fixture.meterRegistry.get(AppMetricNames.VOICE_CLIENT_PLAYBACK_START)
        .tag(AppMetricNames.TAG_MODE, "scheduled_pcm").timer().count()).isEqualTo(1);
    assertThat(fixture.meterRegistry.get(AppMetricNames.VOICE_CLIENT_PLAYBACK_REPORTS)
        .tag(AppMetricNames.TAG_STATUS, "discarded").counter().count()).isEqualTo(4);
    fixture.handler.handleTextMessage(fixture.session, submit("下一轮"));
    assertThat(awaitActionCount(fixture.sent, "turn_started", 2, Duration.ofSeconds(3))).isTrue();
    fixture.handler.handleTextMessage(fixture.session, playbackReport("playback-req-001", turnId));
    assertThat(fixture.meterRegistry.get(AppMetricNames.VOICE_CLIENT_PLAYBACK_START)
        .tag(AppMetricNames.TAG_MODE, "scheduled_pcm").timer().count()).isEqualTo(1);
  }

  private static TextMessage playbackReport(String requestId, String turnId) {
    return new TextMessage("{\"type\":\"control\",\"action\":\"playback_observed\",\"data\":{"
        + "\"clientRequestId\":\"" + requestId + "\",\"turnId\":\"" + turnId + "\","
        + "\"playbackMode\":\"scheduled_pcm\",\"submitToAudioReceivedMs\":120.5,"
        + "\"submitToPlaybackStartMs\":180.75}}");
  }

  private static boolean awaitActionCount(List<String> messages, String action, int count, Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      long actual = messages.stream().filter(message -> message.contains("\"action\":\"" + action + "\"")).count();
      if (actual >= count) {
        return true;
      }
      Thread.sleep(10);
    }
    return false;
  }

    private Fixture fixture(VoiceAsrClient asr, VoiceTtsClient tts, VoiceLlmClient llm,
                            boolean streamingEnabled) throws Exception {
        return fixture(asr, tts, llm, streamingEnabled, 8);
    }

    private Fixture fixture(VoiceAsrClient asr, VoiceTtsClient tts, VoiceLlmClient llm,
                            boolean streamingEnabled, int ttsTimeoutSeconds) throws Exception {
        return fixture(asr, tts, llm, streamingEnabled, ttsTimeoutSeconds, true);
    }

    private Fixture fixture(VoiceAsrClient asr, VoiceTtsClient tts, VoiceLlmClient llm,
                            boolean streamingEnabled, int ttsTimeoutSeconds, boolean hasHistory) throws Exception {
        return fixture(asr, tts, llm, streamingEnabled, ttsTimeoutSeconds, hasHistory, false);
    }

    private Fixture fixture(VoiceAsrClient asr, VoiceTtsClient tts, VoiceLlmClient llm,
                            boolean streamingEnabled, int ttsTimeoutSeconds, boolean hasHistory,
                            boolean framesEnabled) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        VoiceInterviewProperties properties = new VoiceInterviewProperties();
        properties.setLlmStreamingEnabled(streamingEnabled);
        properties.setTtsTimeoutSeconds(ttsTimeoutSeconds);
        properties.getFrameTts().setEnabled(framesEnabled);
        properties.getOpening().setWarmupEnabled(false);
        VoiceInterviewService interviewService = mock(VoiceInterviewService.class);
        VoiceInterviewSessionEntity entity = VoiceInterviewSessionEntity.builder()
            .id(1L).skillId("java-backend").llmProvider("fake").build();
        when(interviewService.getSession(1L)).thenReturn(entity);
        when(interviewService.getConversationHistory("1")).thenReturn(hasHistory
            ? List.of(VoiceInterviewMessageEntity.builder().aiGeneratedText("已有历史问题").build())
            : List.of());

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        VoiceInterviewWebSocketHandler handler = new VoiceInterviewWebSocketHandler(
            mapper, asr, tts, llm, interviewService, properties, new ApplicationMetrics(registry));
        handlers.add(handler);

        WebSocketSession session = mock(WebSocketSession.class);
        List<String> sent = new CopyOnWriteArrayList<>();
        when(session.getId()).thenReturn("fake-ws-1");
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/voice-interview/1"));
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            WebSocketMessage<?> message = invocation.getArgument(0);
            sent.add(String.valueOf(message.getPayload()));
            return null;
        }).when(session).sendMessage(any());
        handler.afterConnectionEstablished(session);
        return new Fixture(handler, session, sent, registry);
    }

    private static TextMessage submit(String text) {
        return new TextMessage("{\"type\":\"control\",\"action\":\"submit\",\"data\":{\"text\":\""
            + text + "\"}}");
    }

  @Test
  @DisplayName("新帧协议首句边合成边出站，后句提前完成仍保持句帧顺序和格式元数据")
  void emitsOrderedFramesWithExplicitPcmMetadata() throws Exception {
    var tts = new FrameClient();
    Fixture fixture = fixture(new FakeAsr(), tts,
        new StreamingFakeLlm(List.of("第一句。", "第二句。")), true, 8, true, true);
    fixture.handler.handleTextMessage(fixture.session, submit("请继续。"));
    var a = tts.await("第一句。"); var b = tts.await("第二句。");
    a.emit(new byte[] {1, 2}); awaitFrames(fixture.sent, 1);
    b.emit(new byte[] {5, 6}); b.succeed();
    assertThat(messagesOfType(fixture.sent, "audio_frame")).hasSize(1);
    a.emit(new byte[] {3, 4}); a.succeed();
    assertThat(awaitMessage(fixture.sent, "turn_completed", Duration.ofSeconds(3))).isTrue();
    var nodes = messagesOfType(fixture.sent, "audio_frame").stream().map(message -> {
      try { return new ObjectMapper().readTree(message); }
      catch (Exception error) { throw new AssertionError(error); }
    }).toList();
    assertThat(nodes.stream().map(n -> n.path("sentenceIndex").asInt() + ":"
        + n.path("frameIndex").asInt() + ":" + n.path("endOfSentence").asBoolean()))
        .containsExactly("0:0:false", "0:1:false", "0:2:true", "1:0:false", "1:1:true");
    assertThat(nodes).allSatisfy(node -> {
      assertThat(node.path("encoding").asText()).isEqualTo("pcm_s16le");
      assertThat(node.path("sampleRate").asInt()).isEqualTo(24000);
      assertThat(node.path("channels").asInt()).isEqualTo(1);
      assertThat(node.path("bitsPerSample").asInt()).isEqualTo(16);
      assertThat(node.path("turnId").asText()).isEqualTo("turn-1");
    });
    assertThat(outboundTurnSequences(fixture.sent)).isSorted().doesNotHaveDuplicates();
    assertThat(fixture.sent).filteredOn(m -> m.contains("\"action\":\"audio_complete\"")).hasSize(1);
    assertThat(tts.wholeCalls).hasValue(0);
  }

  @Test
  @DisplayName("新帧路径取消立即关闭句柄，供应商迟到帧不会写出")
  void closesFrameTaskOnCancellation() throws Exception {
    var tts = new FrameClient();
    Fixture fixture = fixture(new FakeAsr(), tts,
        new StreamingFakeLlm(List.of("第一句。")), true, 8, true, true);
    fixture.handler.handleTextMessage(fixture.session, submit("请继续。"));
    var task = tts.await("第一句。");
    fixture.handler.handleTextMessage(fixture.session, cancel());
    assertThat(task.closed.await(2, TimeUnit.SECONDS)).isTrue();
    task.emit(new byte[] {1, 2});
    assertThat(awaitMessage(fixture.sent, "turn_cancelled", Duration.ofSeconds(2))).isTrue();
    assertThat(messagesOfType(fixture.sent, "audio_frame")).isEmpty();
    assertThat(fixture.sent).noneMatch(m -> m.contains("\"action\":\"turn_completed\""));
    assertThat(tts.wholeCalls).hasValue(0);
  }

  @Test
  @DisplayName("帧已发送后供应商失败，只发唯一失败终态并禁止整段重播")
  void reportsPartialFrameFailureWithoutReplay() throws Exception {
    var tts = new FrameClient();
    Fixture fixture = fixture(new FakeAsr(), tts,
        new StreamingFakeLlm(List.of("第一句。")), true, 8, true, true);
    fixture.handler.handleTextMessage(fixture.session, submit("请继续。"));
    var task = tts.await("第一句。");
    task.emit(new byte[] {1, 2}); awaitFrames(fixture.sent, 1);
    task.result.completeExceptionally(new IllegalStateException("受控供应商失败"));
    assertThat(awaitMessage(fixture.sent, "turn_failed", Duration.ofSeconds(3))).isTrue();
    assertThat(fixture.sent).filteredOn(m -> m.contains("\"action\":\"turn_failed\"")).hasSize(1);
    assertThat(fixture.sent).noneMatch(m -> m.contains("\"action\":\"audio_complete\""));
    assertThat(messagesOfType(fixture.sent, "audio")).isEmpty();
    assertThat(tts.wholeCalls).hasValue(0);
  }

  private static void awaitFrames(List<String> messages, int count) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (messagesOfType(messages, "audio_frame").size() < count && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertThat(messagesOfType(messages, "audio_frame").size()).isGreaterThanOrEqualTo(count);
  }

  private static final class FrameClient implements VoiceFrameTtsClient {
    final Map<String, FrameTask> tasks = new ConcurrentHashMap<>();
    final AtomicInteger wholeCalls = new AtomicInteger();
    @Override public byte[] synthesize(String text) { wholeCalls.incrementAndGet(); return new byte[0]; }
    @Override public VoiceTtsTask prepareFrames(String text, Duration timeout, Consumer<byte[]> callback) {
      var task = new FrameTask(callback); tasks.put(text, task); return task;
    }
    FrameTask await(String text) throws Exception {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (!tasks.containsKey(text) && System.nanoTime() < deadline) { Thread.sleep(5); }
      assertThat(tasks).containsKey(text);
      var task = tasks.get(text);
      assertThat(task.started.await(2, TimeUnit.SECONDS)).isTrue(); return task;
    }
  }

  private static final class FrameTask implements VoiceTtsTask {
    final Consumer<byte[]> callback;
    final CompletableFuture<Summary> result = new CompletableFuture<>();
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch closed = new CountDownLatch(1);
    FrameTask(Consumer<byte[]> callback) { this.callback = callback; }
    @Override public void start() { if (closed.getCount() > 0) { started.countDown(); } }
    void emit(byte[] pcm) { callback.accept(pcm); }
    void succeed() { result.complete(new Summary(2, 1, 0, "fake", 1)); }
    @Override public CompletionStage<Summary> completion() { return result; }
    @Override public void close() { closed.countDown(); result.cancel(false); }
  }

    private static TextMessage cancel() {
        return new TextMessage("{\"type\":\"control\",\"action\":\"cancel\"}");
    }

    private static TextMessage cancel(String requestId) {
        return new TextMessage("{\"type\":\"control\",\"action\":\"cancel\",\"data\":{\"cancelRequestId\":\""
            + requestId + "\"}}");
    }

    private static TextMessage cancelAcknowledged(String requestId, long latencyMs) {
        return new TextMessage("{\"type\":\"control\",\"action\":\"cancel_acknowledged\",\"data\":{\"cancelRequestId\":\""
            + requestId + "\",\"clientAckLatencyMs\":" + latencyMs + "}}");
    }

    private static boolean awaitMessage(List<String> messages, String action, Duration timeout)
        throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (messages.stream().anyMatch(message -> message.contains("\"action\":\"" + action + "\""))) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static List<String> messagesOfType(List<String> messages, String type) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return messages.stream()
            .filter(message -> {
                try {
                    JsonNode root = mapper.readTree(message);
                    return type.equals(root.path("type").asText());
                } catch (Exception ignored) {
                    return false;
                }
            })
            .toList();
    }

    private static List<Integer> audioChunkIndexes(List<String> messages) {
        ObjectMapper mapper = new ObjectMapper();
        return messages.stream()
            .map(message -> {
                try {
                    return mapper.readTree(message);
                } catch (Exception ignored) {
                    return null;
                }
            })
            .filter(node -> node != null && "audio_chunk".equals(node.path("type").asText()))
            .map(node -> node.path("index").asInt())
            .toList();
    }

    private static JsonNode firstControlMessage(List<String> messages, String action) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return messages.stream()
            .map(message -> {
                try {
                    return mapper.readTree(message);
                } catch (Exception ignored) {
                    return null;
                }
            })
            .filter(node -> node != null && "control".equals(node.path("type").asText()))
            .filter(node -> action.equals(node.path("action").asText()))
            .findFirst()
            .orElseThrow();
    }

    private static List<Long> outboundTurnSequences(List<String> messages) {
        ObjectMapper mapper = new ObjectMapper();
        return messages.stream()
            .map(message -> {
                try {
                    return mapper.readTree(message);
                } catch (Exception ignored) {
                    return null;
                }
            })
            .filter(node -> node != null && node.hasNonNull("turnId") && node.has("sequence"))
            .map(node -> node.path("sequence").asLong())
            .toList();
    }

    private record Fixture(VoiceInterviewWebSocketHandler handler, WebSocketSession session, List<String> sent,
                           SimpleMeterRegistry meterRegistry) {
    }

    private static class FakeAsr implements VoiceAsrClient {
        @Override
        public void startTranscription(String sessionId, Consumer<String> onFinal, Consumer<String> onPartial,
                                       Runnable onReady, Consumer<Throwable> onError) {
            onReady.run();
        }

        @Override
        public void restartTranscription(String sessionId, Consumer<String> onFinal, Consumer<String> onPartial,
                                         Runnable onReady, Consumer<Throwable> onError) {
            onReady.run();
        }

        @Override public void sendAudio(String sessionId, byte[] audioData) { }
        @Override public void stopTranscription(String sessionId) { }
        @Override public boolean isReady(String sessionId) { return true; }
    }

    @Test
    @DisplayName("识别未就绪时可显式重连并恢复 ready 状态")
    void explicitlyReconnectsUnavailableAsr() throws Exception {
        CountDownLatch restarted = new CountDownLatch(1);
        VoiceAsrClient asr = new FakeAsr() {
            @Override public boolean isReady(String sessionId) { return false; }
            @Override
            public void restartTranscription(String sessionId, Consumer<String> onFinal, Consumer<String> onPartial,
                                             Runnable onReady, Consumer<Throwable> onError) {
                restarted.countDown();
                onReady.run();
            }
        };
        Fixture fixture = fixture(asr, new FakeTts(new byte[] {1, 2}, null),
                new StreamingFakeLlm(List.of("请继续。")), true);
        fixture.handler.handleTextMessage(fixture.session,
                new TextMessage("{\"type\":\"control\",\"action\":\"reconnect_asr\"}"));

        assertThat(restarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(awaitMessage(fixture.sent, "asr_reconnecting", Duration.ofSeconds(2))).isTrue();
        assertThat(awaitMessage(fixture.sent, "asr_ready", Duration.ofSeconds(2))).isTrue();
    }

    private static final class RecoveringAsr extends FakeAsr {
        private final AtomicInteger sendAttempts = new AtomicInteger();
        private final CountDownLatch restarted = new CountDownLatch(1);
        private final CountDownLatch retriedAudio = new CountDownLatch(1);

        @Override
        public void restartTranscription(String sessionId, Consumer<String> onFinal, Consumer<String> onPartial,
                                         Runnable onReady, Consumer<Throwable> onError) {
            restarted.countDown();
            onReady.run();
        }

        @Override
        public void sendAudio(String sessionId, byte[] audioData) {
            if (sendAttempts.getAndIncrement() == 0) {
                throw new IllegalStateException("ASR append failed: fake disconnect");
            }
            retriedAudio.countDown();
        }
    }

    private static final class FakeTts implements VoiceTtsClient {
        private final byte[] audio;
        private final CountDownLatch release;
        private final CountDownLatch started = new CountDownLatch(1);

        private FakeTts(byte[] audio, CountDownLatch release) {
            this.audio = audio;
            this.release = release;
        }

        @Override
        public byte[] synthesize(String text) {
            started.countDown();
            if (release != null) {
                try {
                    release.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return audio;
        }
    }

    private static class FakeLlm implements VoiceLlmClient {
        private final String reply;

        private FakeLlm(String reply) {
            this.reply = reply;
        }

        @Override
        public String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
            return reply;
        }

        @Override
        public DashscopeLlmService.VoiceLlmResponse chatStreamSentences(
                String userInput, Consumer<String> onToken, Consumer<String> onSentence,
                VoiceInterviewSessionEntity session, List<String> conversationHistory) {
            return new DashscopeLlmService.VoiceLlmResponse(reply, true, true);
        }
    }

    private static final class StreamingFakeLlm implements VoiceLlmClient {
        private final List<String> sentences;

        private StreamingFakeLlm(List<String> sentences) {
            this.sentences = List.copyOf(sentences);
        }

        @Override
        public String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
            return String.join("", sentences);
        }

        @Override
        public DashscopeLlmService.VoiceLlmResponse chatStreamSentences(
                String userInput, Consumer<String> onToken, Consumer<String> onSentence,
                VoiceInterviewSessionEntity session, List<String> conversationHistory) {
            for (String sentence : sentences) {
                onToken.accept(sentence);
                onSentence.accept(sentence);
            }
            return new DashscopeLlmService.VoiceLlmResponse(String.join("", sentences), true, true);
        }
    }

    private static final class DelayedStreamingLlm implements VoiceLlmClient {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public String chat(String userInput, VoiceInterviewSessionEntity session, List<String> conversationHistory) {
            return "迟到回复。";
        }

        @Override
        public DashscopeLlmService.VoiceLlmResponse chatStreamSentences(
                String userInput, Consumer<String> onToken, Consumer<String> onSentence,
                VoiceInterviewSessionEntity session, List<String> conversationHistory) {
            started.countDown();
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        release.await(3, TimeUnit.SECONDS);
                        break;
                    } catch (InterruptedException ignored) {
                        // 模拟上游已收到取消中断但仍会迟到回调的行为。
                        interrupted = true;
                    }
                }
                onToken.accept("迟到回复。");
                onSentence.accept("迟到回复。");
                return new DashscopeLlmService.VoiceLlmResponse("迟到回复。", true, true);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                finished.countDown();
            }
        }
    }

    private static final class OutOfOrderTts implements VoiceTtsClient {
        private final CountDownLatch releaseFirst;
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch secondFinished = new CountDownLatch(1);

        private OutOfOrderTts(CountDownLatch releaseFirst) {
            this.releaseFirst = releaseFirst;
        }

        @Override
        public byte[] synthesize(String text) {
            if (text.startsWith("第一")) {
                firstStarted.countDown();
                try {
                    releaseFirst.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new byte[] {1};
            }
            secondFinished.countDown();
            return new byte[] {2};
        }
    }

    private static final class TimeoutThenEmptyTts implements VoiceTtsClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch firstStarted = new CountDownLatch(1);

        @Override
        public byte[] synthesize(String text) {
            if (calls.getAndIncrement() == 0) {
                firstStarted.countDown();
                try {
                    Thread.sleep(6000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new byte[0];
        }
    }
}
