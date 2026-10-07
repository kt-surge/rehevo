package interview.guide.modules.voiceinterview.service;

import java.util.concurrent.CompletionStage;

public interface VoiceTtsTask extends AutoCloseable {
  void start();

  CompletionStage<Summary> completion();

  /** 取消本地订阅并请求关闭连接；不承诺供应商远端已确认取消。 */
  @Override
  void close();

  record Summary(long bytes, int frames, long firstFrameLatencyNanos,
                 String providerRequestId, Integer providerReportedCharacters) {}
}
