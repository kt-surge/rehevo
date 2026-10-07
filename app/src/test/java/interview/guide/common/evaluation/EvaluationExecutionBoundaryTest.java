package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.StructuredOutputProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("评估截止后停止工作线程与重试")
class EvaluationExecutionBoundaryTest {
  public record Payload(String value) {}

  @Test
  @DisplayName("报告超时返回失败同时向实际批次线程发送中断")
  void timeoutInterruptsBatchWorker() throws Exception {
    var interrupted = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var calls = new AtomicInteger();
    ChatModel model = model();
    doAnswer(call -> {
      calls.incrementAndGet();
      try {
        released.await(10, TimeUnit.SECONDS);
        return response("{}");
      } catch (InterruptedException error) {
        interrupted.countDown();
        Thread.currentThread().interrupt();
        throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "离线受控中断", error);
      } finally {
        finished.countDown();
      }
    }).when(model).call(any(Prompt.class));
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.initialize();
    var registry = new SimpleMeterRegistry();
    try {
      var properties = new InterviewEvaluationProperties();
      properties.setRequestDeadlineSeconds(1);
      properties.setFailedItemRetryLimit(0);
      var service = new UnifiedEvaluationService(new StructuredOutputInvoker(new StructuredOutputProperties(), registry),
          new DefaultResourceLoader(), properties, new ApplicationMetrics(registry), executor);
      var report = service.evaluate(ChatClient.builder(model).build(), "controlled-deadline",
          List.of(new QaRecord(0, "公开问题", "边界", "公开回答")), "");
      assertThat(report.failedQuestions()).isEqualTo(1);
      assertThat(report.scoredQuestions()).isZero();
      assertThat(report.trainingTasks()).isEmpty();
      assertThat(interrupted.await(500, TimeUnit.MILLISECONDS)).isTrue();
      assertThat(finished.await(500, TimeUnit.MILLISECONDS)).isTrue();
      assertThat(calls.get()).isEqualTo(1);
    } finally {
      released.countDown();
      executor.shutdown();
      finished.await(2, TimeUnit.SECONDS);
      registry.close();
    }
  }

  @Test
  @DisplayName("调用前线程已取消时不开始模型请求")
  void preInterruptedThreadSkipsModelCall() {
    var calls = new AtomicInteger();
    ChatModel model = model();
    doAnswer(call -> {
      calls.incrementAndGet();
      return response("{\"value\":\"ok\"}");
    }).when(model).call(any(Prompt.class));
    try {
      Thread.currentThread().interrupt();
      assertThatThrownBy(() -> invoke(model)).isInstanceOf(BusinessException.class);
      assertThat(calls.get()).isZero();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("一次调用中断后不再发起第二次结构重试")
  void interruptedFailureSkipsRetry() {
    var calls = new AtomicInteger();
    ChatModel model = model();
    doAnswer(call -> {
      if (calls.incrementAndGet() == 1) {
        Thread.currentThread().interrupt();
        throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "离线受控中断");
      }
      return response("{\"value\":\"ok\"}");
    }).when(model).call(any(Prompt.class));
    try {
      assertThatThrownBy(() -> invoke(model)).isInstanceOf(BusinessException.class);
      assertThat(calls.get()).isEqualTo(1);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("底层清除中断后截止条件仍阻止下一次模型重试")
  void expiredBudgetSkipsRetryWhenInterruptIsCleared() {
    var allowed = new AtomicBoolean(true);
    var calls = new AtomicInteger();
    ChatModel model = model();
    doAnswer(call -> {
      calls.incrementAndGet();
      allowed.set(false);
      Thread.interrupted();
      throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "离线预算已耗尽");
    }).when(model).call(any(Prompt.class));
    var registry = new SimpleMeterRegistry();
    try {
      var invoker = new StructuredOutputInvoker(new StructuredOutputProperties(), registry);
      assertThatThrownBy(() -> invoker.invoke(ChatClient.builder(model).build(), "公开夹具", "返回 value",
          new BeanOutputConverter<>(Payload.class), ErrorCode.INTERVIEW_EVALUATION_FAILED,
          "受控失败：", "deadline_gate", LoggerFactory.getLogger(getClass()), allowed::get))
          .isInstanceOf(BusinessException.class);
      assertThat(calls.get()).isEqualTo(1);
      assertThat(registry.get("app.ai.structured_output.attempts").tag("context", "deadline_gate")
          .tag("status", "failure").counter().count()).isEqualTo(1);
      assertThat(registry.get("app.ai.structured_output.invocations").tag("context", "deadline_gate")
          .tag("status", "failure").counter().count()).isEqualTo(1);
    } finally {
      registry.close();
    }
  }

  @ParameterizedTest(name = "阶段 {0}")
  @ValueSource(strings = {"summary", "retry"})
  @DisplayName("二次汇总与失败题重试同样遵守报告截止并取消实际线程")
  void secondaryPhaseIsCancelled(String phase) throws Exception {
    var interrupted = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    var finished = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var invoker = mock(StructuredOutputInvoker.class);
    doAnswer(call -> {
      if (calls.incrementAndGet() == 1) {
        if (phase.equals("retry")) return null;
        return new UnifiedEvaluationService.BatchReportDTO(50, "批次评价", List.of(), List.of(),
            List.of(new UnifiedEvaluationService.QuestionEvalDTO(0, 50, "有效评价", 1,
                List.of("公开回答"), List.of(), List.of(), "补充边界", "参考", List.of())));
      }
      try {
        released.await(10, TimeUnit.SECONDS);
        return null;
      } catch (InterruptedException error) {
        interrupted.countDown();
        Thread.currentThread().interrupt();
        throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "受控阶段取消", error);
      } finally {
        finished.countDown();
      }
    }).when(invoker).invoke(any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any(), any());
    var executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(1);
    executor.initialize();
    var registry = new SimpleMeterRegistry();
    try {
      var properties = new InterviewEvaluationProperties();
      properties.setRequestDeadlineSeconds(1);
      properties.setFailedItemRetryLimit(1);
      var service = new UnifiedEvaluationService(invoker, new DefaultResourceLoader(), properties,
          new ApplicationMetrics(registry), executor);
      long started = System.nanoTime();
      var report = service.evaluate(ChatClient.builder(model()).build(), "controlled-phase",
          List.of(new QaRecord(0, "公开问题", "边界", "公开回答")), "");
      assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2500);
      assertThat(report.scoredQuestions()).isEqualTo(phase.equals("summary") ? 1 : 0);
      assertThat(interrupted.await(500, TimeUnit.MILLISECONDS)).isTrue();
      assertThat(finished.await(500, TimeUnit.MILLISECONDS)).isTrue();
      assertThat(calls.get()).isEqualTo(2);
    } finally {
      released.countDown();
      executor.shutdown();
      assertThat(executor.getThreadPoolExecutor().awaitTermination(2, TimeUnit.SECONDS)).isTrue();
      registry.close();
    }
  }

  private ChatModel model() {
    var model = mock(ChatModel.class);
    when(model.getOptions()).thenReturn(ChatOptions.builder().build());
    return model;
  }

  private Payload invoke(ChatModel model) {
    var invoker = new StructuredOutputInvoker(new StructuredOutputProperties(), null);
    return invoker.invoke(ChatClient.builder(model).build(), "公开离线夹具", "返回 value",
        new BeanOutputConverter<>(Payload.class), ErrorCode.INTERVIEW_EVALUATION_FAILED,
        "受控失败：", "deadline_gate", LoggerFactory.getLogger(getClass()));
  }

  private ChatResponse response(String body) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(body))));
  }
}
