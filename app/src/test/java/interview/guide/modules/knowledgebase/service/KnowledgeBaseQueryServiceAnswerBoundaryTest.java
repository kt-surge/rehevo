package interview.guide.modules.knowledgebase.service;

import java.io.IOException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/** Only answer presentation is exercised; retrieval, DB and real models are not invoked. */
class KnowledgeBaseQueryServiceAnswerBoundaryTest {
  private final KnowledgeBaseQueryService subject = mock(KnowledgeBaseQueryService.class, CALLS_REAL_METHODS);

  @Test
  @DisplayName("有依据的部分回答含信息不足时仍保留已有事实")
  void keepsSupportedPartialAnswer() throws Exception {
    String answer = "文档明确参数需要非空。跨语言事务的信息不足，无法确认。";
    assertThat(normalize(answer)).isEqualTo(answer);
  }

  @Test
  @DisplayName("保留模型拒答理由而不改成未检索到文档")
  void keepsRefusalReason() throws Exception {
    String answer = "当前资料信息不足，无法根据提供内容回答跨语言事务支持情况。";
    assertThat(normalize(answer)).isEqualTo(answer);
  }

  @Test
  @DisplayName("短回答的首片段在上游完成前即可到达且取消传给上游")
  void shortAnswerArrivesBeforeCompletionAndCancelsUpstream() throws Exception {
    List<String> received = new ArrayList<>();
    AtomicBoolean cancelled = new AtomicBoolean();
    Flux<String> raw = Flux.concat(Flux.just("参数不能为null。"), Flux.<String>never())
        .doOnCancel(() -> cancelled.set(true));
    Disposable subscription = stream(raw).subscribe(received::add);
    try {
      assertThat(received).containsExactly("参数不能为null。");
    } finally {
      subscription.dispose();
    }
    assertThat(cancelled).isTrue();
  }

  @Test
  @DisplayName("跨Token出现信息不足不吞掉前面的证据或后面的缺失说明")
  void preservesPartialStreamExactly() throws Exception {
    List<String> result = stream(Flux.just("已知提交后才ACK。", "租约超时信息", "不足，不能确定秒数。"))
        .collectList().block(Duration.ofSeconds(1));
    assertThat(String.join("", result)).isEqualTo("已知提交后才ACK。租约超时信息不足，不能确定秒数。");
  }

  @Test
  @DisplayName("非空回答的前导空格和Markdown分片顺序保持")
  void preservesLeadingWhitespaceAndMarkdown() throws Exception {
    List<String> result = stream(Flux.just("\n", " ", "-", " ", "证据A。\n", "\n"))
        .collectList().block(Duration.ofSeconds(1));
    assertThat(String.join("", result)).isEqualTo("\n - 证据A。\n\n");
  }

  @Test
  @DisplayName("空流及仅空白流与同步空回答使用相同提示")
  void emptyAndWhitespaceOnlyUseSameFallback() throws Exception {
    String fallback = normalize(" ");
    assertThat(stream(Flux.empty()).collectList().block(Duration.ofSeconds(1))).containsExactly(fallback);
    assertThat(stream(Flux.just(" ", "\n")).collectList().block(Duration.ofSeconds(1))).containsExactly(fallback);
  }

  @Test
  @DisplayName("上游失败透传错误并保留已经交付的有依据片段")
  void propagatesErrorWithoutFalseRefusal() throws Exception {
    IOException failure = new IOException("controlled upstream failure");
    List<String> received = new ArrayList<>();
    List<Throwable> errors = new ArrayList<>();
    stream(Flux.concat(Flux.just("有依据的事实A。"), Flux.error(failure)))
        .subscribe(received::add, errors::add);
    assertThat(received).containsExactly("有依据的事实A。");
    assertThat(errors).containsExactly(failure);
  }

  private String normalize(String value) throws Exception {
    Method method = KnowledgeBaseQueryService.class.getDeclaredMethod("normalizeAnswer", String.class);
    method.setAccessible(true);
    return (String) method.invoke(subject, value);
  }

  @SuppressWarnings("unchecked")
  private Flux<String> stream(Flux<String> value) throws Exception {
    Method method = KnowledgeBaseQueryService.class.getDeclaredMethod("normalizeStreamOutput", Flux.class);
    method.setAccessible(true);
    return (Flux<String>) method.invoke(subject, value);
  }
}
