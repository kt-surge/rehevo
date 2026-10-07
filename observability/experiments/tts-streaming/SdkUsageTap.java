package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.common.DashScopeResult;
import com.alibaba.dashscope.common.ResultCallback;
import com.alibaba.dashscope.common.Status;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.protocol.FullDuplexClient;
import com.alibaba.dashscope.protocol.FullDuplexRequest;
import io.reactivex.Flowable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仅实验使用：2.22.7 在转换 SpeechSynthesisResult 时丢弃 Token 用量。
 * 在已冻结 SDK 的原始结果边界观察数字字段，所有请求与回调仍由原客户端执行。
 * 不读取/导出 FullDuplexRequest 或凭据，不部署到产品。
 */
final class SdkUsageTap implements FullDuplexClient {
  private final FullDuplexClient delegate;
  private final List<Map<String, Object>> usage = new ArrayList<>();
  private final List<Map<String, Object>> close = new ArrayList<>();
  private final List<Map<String, Object>> cancel = new ArrayList<>();
  private int providerInvocations;
  private int binaryEvents;

  private SdkUsageTap(FullDuplexClient delegate) {
    this.delegate = delegate;
  }

  static SdkUsageTap install(SpeechSynthesizer synthesizer) throws ReflectiveOperationException {
    var api = synthesizer.getDuplexApi();
    Field field = api.getClass().getDeclaredField("client");
    field.setAccessible(true);
    var tap = new SdkUsageTap((FullDuplexClient) field.get(api));
    field.set(api, tap);
    if (field.get(api) != tap) {
      throw new IllegalStateException("SDK tap installation failed; no provider request allowed");
    }
    return tap;
  }

  private synchronized void observe(DashScopeResult result) {
    if (Boolean.TRUE.equals(result.isBinaryOutput())) { binaryEvents++; }
    if (result.getUsage() == null || !result.getUsage().isJsonObject()) {
      return;
    }
    var value = result.getUsage().getAsJsonObject();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("requestId", result.getRequestId());
    row.put("event", result.getEvent());
    for (String key : List.of("input_tokens", "output_tokens", "total_tokens", "characters")) {
      if (value.has(key) && !value.get(key).isJsonNull()) {
        row.put(key, value.get(key).getAsLong());
      }
    }
    usage.add(row);
  }

  private ResultCallback<DashScopeResult> wrap(ResultCallback<DashScopeResult> callback) {
    return new ResultCallback<>() {
      @Override public void onOpen(Status status) { callback.onOpen(status); }
      @Override public void onEvent(DashScopeResult result) {
        observe(result);
        callback.onEvent(result);
      }
      @Override public void onComplete() { callback.onComplete(); }
      @Override public void onError(Exception error) { callback.onError(error); }
    };
  }

  @Override
  public DashScopeResult streamIn(FullDuplexRequest request) throws NoApiKeyException {
    synchronized (this) { providerInvocations++; }
    var result = delegate.streamIn(request);
    observe(result);
    return result;
  }

  @Override
  public void streamIn(FullDuplexRequest request, ResultCallback<DashScopeResult> callback)
      throws NoApiKeyException {
    synchronized (this) { providerInvocations++; }
    delegate.streamIn(request, wrap(callback));
  }

  @Override
  public Flowable<DashScopeResult> duplex(FullDuplexRequest request) throws NoApiKeyException {
    synchronized (this) { providerInvocations++; }
    return delegate.duplex(request).doOnNext(this::observe);
  }

  @Override
  public void duplex(FullDuplexRequest request, ResultCallback<DashScopeResult> callback)
      throws NoApiKeyException {
    synchronized (this) { providerInvocations++; }
    delegate.duplex(request, wrap(callback));
  }

  @Override
  public synchronized boolean close(int code, String reason) {
    long began = System.nanoTime();
    boolean accepted = delegate.close(code, reason);
    close.add(Map.of("accepted", accepted, "elapsedMs", (System.nanoTime() - began) / 1_000_000.0));
    return accepted;
  }

  @Override public synchronized void cancel() {
    long began = System.nanoTime();
    delegate.cancel();
    cancel.add(Map.of("elapsedMs", (System.nanoTime() - began) / 1_000_000.0));
  }

  synchronized Map<String, Object> snapshot() {
    return Map.of("rawProviderUsageEvents", List.copyOf(usage), "closeRequests", List.copyOf(close),
        "cancelRequests", List.copyOf(cancel), "providerInvocations", providerInvocations,
        "rawBinaryEvents", binaryEvents, "aggregationAssumed", false,
        "remoteCloseAcknowledgementObserved", false);
  }
}
