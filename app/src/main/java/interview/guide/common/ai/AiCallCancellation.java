package interview.guide.common.ai;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** 同步模型任务绑定实际 HTTP Call；取消只影响该任务，不取消共享客户端的其他请求。 */
public final class AiCallCancellation {
  private static final Logger log = LoggerFactory.getLogger(AiCallCancellation.class);
  private static final ThreadLocal<AiCallCancellation> CURRENT = new ThreadLocal<>();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

  @FunctionalInterface
  public interface Binding extends AutoCloseable {
    @Override
    void close();
  }

  public Binding bind() {
    AiCallCancellation previous = CURRENT.get();
    CURRENT.set(this);
    return () -> {
      callbacks.clear();
      if (previous == null) CURRENT.remove();
      else CURRENT.set(previous);
    };
  }

  public static void registerCurrent(Runnable cancelCall) {
    AiCallCancellation scope = CURRENT.get();
    if (scope == null) return;
    scope.callbacks.add(cancelCall);
    if (scope.cancelled.get()) {
      cancelCall.run();
      throw new BusinessException(ErrorCode.AI_SERVICE_TIMEOUT, "模型任务已取消，不开始 HTTP 请求");
    }
  }

  public void cancel() {
    if (!cancelled.compareAndSet(false, true)) return;
    for (Runnable callback : callbacks) {
      try {
        callback.run();
      } catch (Exception error) {
        log.warn("取消模型 HTTP 请求失败", error);
      }
    }
  }
}
