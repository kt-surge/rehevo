package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试 LLM 服务测试")
class DashscopeLlmServiceTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private VoiceInterviewPromptService promptService;
  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private PromptSanitizer promptSanitizer;

  private DashscopeLlmService service;
  private VoiceInterviewProperties properties;
  private VoiceInterviewSessionEntity session;

  @BeforeEach
  void setUp() {
    properties = new VoiceInterviewProperties();
    service = new DashscopeLlmService(
        llmProviderRegistry,
        promptService,
        resumeRepository,
        properties,
        promptSanitizer
    );
    session = VoiceInterviewSessionEntity.builder()
        .id(1L)
        .skillId("java-backend")
        .llmProvider("dashscope")
        .build();
    when(promptService.generateSystemPromptWithContext("java-backend", null))
        .thenReturn("system prompt");
    when(promptSanitizer.sanitize("请介绍项目")).thenReturn("请介绍项目");
    when(promptSanitizer.wrapWithDelimiters("input", "请介绍项目"))
        .thenReturn("<input>请介绍项目</input>");
  }

  @Test
  @DisplayName("模型超时返回稳定的用户提示")
  void mapTimeoutToUserMessage() {
    when(llmProviderRegistry.getVoiceChatClient("dashscope"))
        .thenThrow(new IllegalStateException("request timeout"));

    String result = service.chat("请介绍项目", session, List.of());

    assertThat(result).isEqualTo("AI 服务响应超时，请稍后重试");
    verify(llmProviderRegistry).getVoiceChatClient("dashscope");
  }

  @Test
  @DisplayName("模型认证失败返回可操作提示")
  void mapAuthenticationFailureToUserMessage() {
    when(llmProviderRegistry.getVoiceChatClient("dashscope"))
        .thenThrow(new IllegalStateException("403 Authentication failed"));

    String result = service.chat("请介绍项目", session, List.of());

    assertThat(result).isEqualTo("AI 服务认证失败，请检查 API Key 配置");
  }

  @Test
  @DisplayName("流式聚合空 Chunk 异常允许降级为非流式调用")
  void identifiesStreamingAggregationFailure() {
    assertThat(DashscopeLlmService.isStreamingAggregationFailure(
        new IllegalStateException("stream failed", new NoSuchElementException("No value present"))
    )).isTrue();
    assertThat(DashscopeLlmService.isStreamingAggregationFailure(
        new IllegalStateException("request timeout")
    )).isFalse();

    when(llmProviderRegistry.getVoiceChatClient("dashscope"))
        .thenThrow(new IllegalStateException("request timeout"));
    assertThat(service.chat("请介绍项目", session, List.of()))
        .isEqualTo("AI 服务响应超时，请稍后重试");
  }

  @Test
  @DisplayName("完整句与尾巴分开下发且字幕终稿与语音内容一致")
  void streamsOnlyCompleteSentences() {
    stubStream(Flux.just("第一句。第二", "句？尾巴"));
    List<String> sentences = new ArrayList<>();
    List<String> subtitles = new ArrayList<>();

    var result = service.chatStreamSentences(
        "请介绍项目", subtitles::add, sentences::add, session, List.of());

    assertThat(result.success()).isTrue();
    assertThat(sentences).containsExactly("第一句。", "第二句？");
    assertThat(String.join("", sentences)).isEqualTo(result.content());
    assertThat(subtitles).last().isEqualTo(result.content());
  }

  @Test
  @DisplayName("有完整问题后仍消费上游终态，不因字符预算丢掉最终用量")
  void consumesTerminalChunkAfterQuestion() {
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicBoolean completed = new AtomicBoolean();
    stubStream(Flux.just("版本号如何避免旧结果覆盖？", "补充文字不应继续播报。")
        .doOnCancel(() -> cancelled.set(true)).doOnComplete(() -> completed.set(true)));
    List<String> sentences = new ArrayList<>();

    var result = service.chatStreamSentences(
        "请介绍项目", null, sentences::add, session, List.of());

    assertThat(cancelled).isFalse();
    assertThat(completed).isTrue();
    assertThat(result.content()).isEqualTo("版本号如何避免旧结果覆盖？");
    assertThat(String.join("", sentences)).isEqualTo(result.content());
  }

  @Test
  @DisplayName("流式空响应失败且不发送占位语音或成功字幕")
  void emptyStreamHasConsistentFallback() {
    stubStream(Flux.empty());
    List<String> sentences = new ArrayList<>();
    List<String> subtitles = new ArrayList<>();

    var result = service.chatStreamSentences(
        "请介绍项目", subtitles::add, sentences::add, session, List.of());

    assertThat(result.success()).isFalse();
    assertThat(sentences).isEmpty();
    assertThat(subtitles).isEmpty();
  }

  @Test
  @DisplayName("部分句子已交给音频链路后聚合失败不重启模型重播")
  void partialStreamFailureDoesNotReplay() {
    ChatClient.ChatClientRequestSpec request = stubStream(Flux.concat(
        Flux.just("已播出的完整问题？"), Flux.error(new NoSuchElementException("No value present"))));
    List<String> sentences = new ArrayList<>();

    var result = service.chatStreamSentences(
        "请介绍项目", null, sentences::add, session, List.of());

    assertThat(result.success()).isFalse();
    assertThat(sentences).containsExactly("已播出的完整问题？");
    verify(request, never()).call();
  }

  @Test
  @DisplayName("尚未播出时允许聚合兼容降级并补发最终字幕")
  void emptyAggregationFailureCanFallback() {
    ChatClient.ChatClientRequestSpec request = stubStream(
        Flux.error(new NoSuchElementException("No value present")));
    ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
    when(request.call()).thenReturn(response);
    when(response.content()).thenReturn("降级后的问题？");
    List<String> sentences = new ArrayList<>();
    List<String> subtitles = new ArrayList<>();

    var result = service.chatStreamSentences(
        "请介绍项目", subtitles::add, sentences::add, session, List.of());

    assertThat(result.success()).isTrue();
    assertThat(result.streaming()).isFalse();
    assertThat(sentences).containsExactly(result.content());
    assertThat(subtitles).containsExactly(result.content());
  }

  @Test
  @SuppressWarnings("unchecked")
  @DisplayName("显式角色请求保留历史顺序，当前User不夹带模型假设")
  void sendsRoleHistoryOutsideCurrentUser() {
    properties.setRoleHistoryEnabled(true);
    var request = stubStream(Flux.just("你打算如何回收已取消轮次的资源？"));
    String assumption = "面试官：标识存在内存还是Redis？";
    String answer = "候选人：我尚未确认存储位置。";
    when(promptSanitizer.sanitize(assumption)).thenReturn(assumption);
    when(promptSanitizer.sanitize(answer)).thenReturn(answer);
    var result = service.chatStreamSentences("请介绍项目", null, null, session, List.of(assumption, answer));
    assertThat(result.success()).isTrue();
    ArgumentCaptor<List<Message>> history = ArgumentCaptor.forClass(List.class);
    verify(request).messages(history.capture());
    assertThat(history.getValue()).extracting(Message::getMessageType)
        .containsExactly(MessageType.ASSISTANT, MessageType.USER);
    assertThat(history.getValue()).extracting(Message::getText).containsExactly(assumption, answer);
    verify(request).user("用户：<input>请介绍项目</input>");
  }

  private ChatClient.ChatClientRequestSpec stubStream(Flux<String> tokens) {
    return stubStream(tokens, false);
  }

  @Test
  @DisplayName("实际超长草稿只压缩一次，技术条件保留，原残缺文本不播")
  void compressesOversizeQuestionOnceBeforeAnyAudio() {
    String draft = "你刚才提到的 WebSocket 和语音合成似乎偏离了 Redis Stream 的上下文。请回到上一个问题：当消费者处理完新版本后，Redis Stream 中残留的旧版本未确认消息（Pending），你是如何清理或确保它们不会干扰最终一致性？";
    stubStream(Flux.just(draft));
    stubRepair(draft, "消费者已完成新版本，Redis Stream 仍残留旧版本 Pending 消息，如何处理以保证最终一致性？");
    List<String> sentences = new ArrayList<>();

    var result = service.chatStreamSentences("请介绍项目", null, sentences::add, session, List.of());

    assertThat(result.success()).isTrue();
    assertThat(result.content()).hasSizeLessThanOrEqualTo(120).endsWith("？")
        .contains("新版本", "旧版本", "Pending", "最终一致性").doesNotContain("…");
    assertThat(sentences).containsExactly(result.content());
    verify(promptService).generateQuestionRepairPrompt(draft);
    verify(llmProviderRegistry).getPlainChatClient("dashscope");
  }

  @Test
  @DisplayName("一次压缩仍不完整则失败，不循环重试且不播放占位语")
  void invalidCompressionFailsWithoutReplay() {
    stubStream(Flux.just("未完成的技术问题"));
    stubRepair("未完成的技术问题", "压缩后仍未问完");
    List<String> sentences = new ArrayList<>();

    var result = service.chatStreamSentences("请介绍项目", null, sentences::add, session, List.of());

    assertThat(result.success()).isFalse();
    assertThat(sentences).isEmpty();
    verify(llmProviderRegistry).getPlainChatClient("dashscope");
  }

  @Test
  @DisplayName("上游持续不结束时绝对截止时间终止订阅，不启动额外压缩")
  void cancelsNeverEndingStreamAtDeadline() {
    properties.setAiQuestionTimeoutSeconds(1);
    AtomicBoolean cancelled = new AtomicBoolean();
    stubStream(Flux.<String>never().doOnCancel(() -> cancelled.set(true)));

    var result = service.chatStreamSentences("请介绍项目", null, null, session, List.of());

    assertThat(result.success()).isFalse();
    assertThat(result.content()).contains("超时");
    assertThat(cancelled).isTrue();
    verify(llmProviderRegistry, never()).getPlainChatClient(anyString());
  }

  private void stubRepair(String draft, String repaired) {
    ChatClient client = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.StreamResponseSpec response = mock(ChatClient.StreamResponseSpec.class);
    when(llmProviderRegistry.getPlainChatClient("dashscope")).thenReturn(client);
    when(client.prompt()).thenReturn(request);
    when(request.system(anyString())).thenReturn(request);
    when(promptService.generateQuestionRepairPrompt(draft)).thenReturn("安全压缩请求");
    when(request.user("安全压缩请求")).thenReturn(request);
    when(request.options(any(ChatOptions.Builder.class))).thenReturn(request);
    when(request.stream()).thenReturn(response);
    when(response.content()).thenReturn(Flux.just(repaired));
  }

  @Test
  @DisplayName("兼容降级的阻塞调用也受同一截止时间约束，超时中断工作线程")
  void boundsNonStreamingFallbackAndInterruptsWorker() throws Exception {
    properties.setAiQuestionTimeoutSeconds(1);
    var request = stubStream(Flux.error(new NoSuchElementException("No value present")));
    var response = mock(ChatClient.CallResponseSpec.class);
    CountDownLatch workerStopped = new CountDownLatch(1);
    AtomicBoolean interrupted = new AtomicBoolean();
    when(request.call()).thenReturn(response);
    when(response.content()).thenAnswer(invocation -> {
      try { Thread.sleep(5000); }
      catch (InterruptedException error) { interrupted.set(true); Thread.currentThread().interrupt(); }
      finally { workerStopped.countDown(); }
      return "迟到的问题？";
    });
    List<String> sentences = new ArrayList<>();

    var result = service.chatStreamSentences("请介绍项目", null, sentences::add, session, List.of());

    assertThat(result.success()).isFalse();
    assertThat(result.content()).contains("超时");
    assertThat(workerStopped.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(interrupted).isTrue();
    assertThat(sentences).isEmpty();
  }

  @Test
  @DisplayName("岗位规则已预加载时直接使用无工具客户端并保持句级流式输出")
  void streamsWithPreloadedSkillWithoutToolRoundTrip() {
    stubStream(Flux.just("请解释版本围栏", "如何阻止旧任务覆盖？"), true);
    List<String> sentences = new ArrayList<>();

    var result = service.chatStreamSentences("请介绍项目", null, sentences::add, session, List.of());

    assertThat(result.success()).isTrue();
    assertThat(result.streaming()).isTrue();
    assertThat(sentences).containsExactly("请解释版本围栏如何阻止旧任务覆盖？");
    verify(llmProviderRegistry).getPlainChatClient("dashscope");
    verify(llmProviderRegistry, never()).getVoiceChatClient("dashscope");
  }

  private ChatClient.ChatClientRequestSpec stubStream(Flux<String> tokens, boolean preloaded) {
    ChatClient client = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.StreamResponseSpec response = mock(ChatClient.StreamResponseSpec.class);
    if (preloaded) {
      when(promptService.isSkillPreloaded("java-backend")).thenReturn(true);
      when(llmProviderRegistry.getPlainChatClient("dashscope")).thenReturn(client);
    } else {
      when(llmProviderRegistry.getVoiceChatClient("dashscope")).thenReturn(client);
    }
    when(client.prompt()).thenReturn(request);
    when(request.system(anyString())).thenReturn(request);
    when(request.messages(anyList())).thenReturn(request);
    when(request.user(anyString())).thenReturn(request);
    when(request.options(any(ChatOptions.Builder.class))).thenReturn(request);
    when(request.stream()).thenReturn(response);
    when(response.content()).thenReturn(tokens);
    return request;
  }
}
