package interview.guide.modules.voiceinterview.turn;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/** 轮次终态只清理一次；终态后的迟到注册立即清理。 */
@Slf4j
public final class VoiceTurnResources implements AutoCloseable {
  private final List<AutoCloseable> resources = new ArrayList<>();
  private boolean closed;

  public boolean register(AutoCloseable resource) {
    synchronized (this) {
      if (!closed) { resources.add(resource); return true; }
    }
    release(resource);
    return false;
  }

  @Override
  public void close() {
    List<AutoCloseable> snapshot;
    synchronized (this) {
      if (closed) { return; }
      closed = true;
      snapshot = List.copyOf(resources);
      resources.clear();
    }
    snapshot.forEach(this::release);
  }

  private void release(AutoCloseable resource) {
    try { resource.close(); }
    catch (Exception error) { log.warn("语音轮次资源释放失败", error); }
  }
}
