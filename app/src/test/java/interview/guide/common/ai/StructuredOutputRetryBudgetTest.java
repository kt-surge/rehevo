package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("结构化输出重试预算使用真实 Spring AI 链而非外部模型")
class StructuredOutputRetryBudgetTest {
  public record Payload(String value) {}

  @Test
  @DisplayName("连续非法 JSON 最多调用配置次数，不叠加 SDK 自校验重试")
  void invalidJsonIsBounded() {
    AtomicInteger calls = new AtomicInteger();
    ChatClient client = client(calls, "not json", "not json");
    StructuredOutputInvoker invoker = invoker(null, true);
    assertThatThrownBy(() -> invoke(invoker, client)).isInstanceOf(BusinessException.class);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("合法 JSON 但缺必填字段时仍失败，不能为限制次数跳过 Schema")
  void invalidSchemaIsRejected() {
    AtomicInteger calls = new AtomicInteger();
    ChatClient client = client(calls, "{}", "{}");
    assertThatThrownBy(() -> invoke(invoker(null, true), client)).isInstanceOf(BusinessException.class);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("第二次修复成功时实际调用数与中央尝试指标一致")
  void recoveryMatchesCentralMetrics() {
    AtomicInteger calls = new AtomicInteger();
    ChatClient client = client(calls, "not json", "{\"value\":\"ok\"}");
    var registry = new SimpleMeterRegistry();
    try {
      Payload result = invoke(invoker(registry, true), client);
      assertThat(result.value()).isEqualTo("ok");
      assertThat(calls.get()).isEqualTo(2);
      assertThat(registry.get("app.ai.structured_output.attempts").tag("context", "retry_budget")
          .tag("status", "failure").counter().count()).isEqualTo(1);
      assertThat(registry.get("app.ai.structured_output.attempts").tag("context", "retry_budget")
          .tag("status", "success").counter().count()).isEqualTo(1);
    } finally {
      registry.close();
    }
  }

  @Test
  @DisplayName("显式关闭 Schema 时仍保留既有转换路径和一次成功调用")
  void schemaDisabledPathIsPreserved() {
    AtomicInteger calls = new AtomicInteger();
    Payload result = invoke(invoker(null, false), client(calls, "{\"value\":\"ok\"}", "unused"));
    assertThat(result.value()).isEqualTo("ok");
    assertThat(calls.get()).isEqualTo(1);
  }

  private ChatClient client(AtomicInteger calls, String first, String following) {
    ChatModel model = mock(ChatModel.class);
    when(model.getOptions()).thenReturn(ChatOptions.builder().build());
    doAnswer(call -> new ChatResponse(List.of(new Generation(new AssistantMessage(
        calls.incrementAndGet() == 1 ? first : following)))))
        .when(model).call(any(Prompt.class));
    return ChatClient.builder(model).build();
  }

  private StructuredOutputInvoker invoker(SimpleMeterRegistry registry, boolean validate) {
    var properties = new StructuredOutputProperties();
    properties.setStructuredMaxAttempts(2);
    properties.setStructuredSchemaValidationEnabled(validate);
    return new StructuredOutputInvoker(properties, registry);
  }

  private Payload invoke(StructuredOutputInvoker invoker, ChatClient client) {
    return invoker.invoke(client, "公开离线夹具", "返回 value", new BeanOutputConverter<>(Payload.class),
        ErrorCode.INTERVIEW_EVALUATION_FAILED, "受控失败：", "retry_budget",
        LoggerFactory.getLogger(getClass()));
  }
}
