package interview.guide.modules.knowledgebase.service;

import interview.guide.common.config.VectorTaskRecoveryProperties;
import interview.guide.modules.knowledgebase.model.VectorTaskExecutionDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** 仅对有效执行权续约；数据库不可用时保守失权，提交仍由数据库 fencing 验证。 */
@Service
@Slf4j
public class VectorTaskExecutionHeartbeat {
  private final KnowledgeBaseVectorTaskService tasks;
  private final VectorTaskRecoveryProperties properties;
  private final TaskScheduler scheduler;

  public VectorTaskExecutionHeartbeat(KnowledgeBaseVectorTaskService tasks,
      VectorTaskRecoveryProperties properties,
      @Qualifier("vectorExecutionTaskScheduler") TaskScheduler scheduler) {
    this.tasks = tasks;
    this.properties = properties;
    this.scheduler = scheduler;
  }

  public Guard monitor(VectorTaskExecutionDTO execution) {
    Guard guard = new Guard();
    guard.future = scheduler.scheduleWithFixedDelay(() -> {
      try {
        if (!tasks.renewExecution(execution)) { guard.revoke(); }
      } catch (Exception error) {
        guard.revoke();
        log.error("执行心跳失败，停止后续计算和有效提交: kbId={}, fence={}", execution.kbId(), execution.fence(), error);
      }
    }, Instant.now().plusMillis(properties.getHeartbeatDelayMs()), Duration.ofMillis(properties.getHeartbeatDelayMs()));
    return guard;
  }

  public static final class Guard implements AutoCloseable {
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private volatile ScheduledFuture<?> future;

    public boolean valid() { return valid.get() && !Thread.currentThread().isInterrupted(); }
    private void revoke() {
      valid.set(false);
      if (future != null) { future.cancel(false); }
    }
    @Override public void close() { revoke(); }
  }
}
