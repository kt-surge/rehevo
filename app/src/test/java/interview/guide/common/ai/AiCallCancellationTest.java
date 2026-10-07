package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("模型 HTTP 取消范围隔离")
class AiCallCancellationTest {
  @Test
  @DisplayName("嵌套任务恢复外层绑定，取消内层不影响外层请求")
  void nestedScopesRemainIndependent() {
    var outer = new AiCallCancellation();
    var inner = new AiCallCancellation();
    var outerCalls = new AtomicInteger();
    var innerCalls = new AtomicInteger();
    try (var outerBinding = outer.bind()) {
      AiCallCancellation.registerCurrent(outerCalls::incrementAndGet);
      try (var innerBinding = inner.bind()) {
        AiCallCancellation.registerCurrent(innerCalls::incrementAndGet);
        inner.cancel();
        assertThat(innerCalls.get()).isEqualTo(1);
        assertThat(outerCalls.get()).isZero();
      }
      AiCallCancellation.registerCurrent(outerCalls::incrementAndGet);
      outer.cancel();
      outer.cancel();
      assertThat(outerCalls.get()).isEqualTo(2);
      assertThat(innerCalls.get()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("注册之前已取消时立即取消请求并拒绝开始网络调用")
  void cancelledScopeRejectsLateRequest() {
    var scope = new AiCallCancellation();
    var calls = new AtomicInteger();
    scope.cancel();
    try (var binding = scope.bind()) {
      assertThatThrownBy(() -> AiCallCancellation.registerCurrent(calls::incrementAndGet))
          .isInstanceOf(BusinessException.class);
      assertThat(calls.get()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("任务完成清理回调与线程绑定，后续普通请求不加入旧任务")
  void completedScopeDoesNotCaptureLaterRequests() {
    var scope = new AiCallCancellation();
    var calls = new AtomicInteger();
    try (var binding = scope.bind()) {
      AiCallCancellation.registerCurrent(calls::incrementAndGet);
    }
    AiCallCancellation.registerCurrent(calls::incrementAndGet);
    scope.cancel();
    assertThat(calls.get()).isZero();
  }
}
