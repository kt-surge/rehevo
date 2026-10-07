package interview.guide.modules.voiceinterview.turn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("轮次资源关闭竞态")
class VoiceTurnResourcesTest {
  @Test
  @DisplayName("终态清理一次，迟到注册立即关闭且不保留句柄")
  void releasesLateRegistration() {
    var resources = new VoiceTurnResources();
    var count = new AtomicInteger();
    assertThat(resources.register(count::incrementAndGet)).isTrue();
    resources.close(); resources.close();
    assertThat(count).hasValue(1);
    assertThat(resources.register(count::incrementAndGet)).isFalse();
    resources.close(); assertThat(count).hasValue(2);
  }

  @Test
  @DisplayName("一个句柄关闭失败不阻止其余句柄释放")
  void continuesCleanupOnFailure() {
    var resources = new VoiceTurnResources();
    var count = new AtomicInteger();
    resources.register(() -> { throw new IllegalStateException("受控关闭失败"); });
    resources.register(count::incrementAndGet);
    resources.close(); assertThat(count).hasValue(1);
  }
}
