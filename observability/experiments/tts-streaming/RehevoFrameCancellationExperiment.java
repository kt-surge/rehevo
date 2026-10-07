package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.utils.Constants;
import com.google.gson.GsonBuilder;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** 实际 SDK 生命周期诊断；不把本地 close 返回当作供应商取消确认。 */
public class RehevoFrameCancellationExperiment {
  private static final String TEXT = "请详细说明用户在面试官播报过程中点击中断时，服务怎样清理音频队列，"
      + "终止尚未完成的合成，并避免上一轮的迟到音频继续播放。同时说明连接尚未建立时发生取消的情况。";
  private static final class Client extends QwenTtsService {
    SdkUsageTap tap;
    Client(VoiceInterviewProperties properties) { super(properties); }
    @Override SpeechSynthesizer createSynthesizer(SpeechSynthesisParam parameters) {
      var synthesizer = super.createSynthesizer(parameters);
      try { tap = SdkUsageTap.install(synthesizer); }
      catch (ReflectiveOperationException error) { throw new IllegalStateException("Usage tap unavailable", error); }
      return synthesizer;
    }
  }

  private static void write(Path output, Object value) throws Exception {
    Files.writeString(output.resolve("results.json"),
        new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(value), StandardCharsets.UTF_8);
  }

  private static Map<String, Object> invoke(String scenario, VoiceInterviewProperties properties) throws Exception {
    var client = new Client(properties);
    var first = new CountDownLatch(1);
    var frames = new AtomicInteger();
    var late = new AtomicInteger();
    var closedAt = new AtomicLong();
    long began = System.nanoTime();
    var task = client.prepareFrames(TEXT, Duration.ofMillis(scenario.equals("deadline-before-first") ? 100 : 12000), pcm -> {
      frames.incrementAndGet();
      if (closedAt.get() != 0) { late.incrementAndGet(); }
      first.countDown();
    });
    var started = new CompletableFuture<Void>();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("scenario", scenario); row.put("startedAt", Instant.now().toString());
    Thread starter = null;
    if (!scenario.equals("before-start")) {
      starter = Thread.ofVirtual().name("tts-cancellation-probe").start(() -> {
        try { task.start(); started.complete(null); }
        catch (Exception error) { started.completeExceptionally(error); }
      });
    }
    if (scenario.equals("after-first")) {
      row.put("firstObservedBeforeCancel", first.await(5, TimeUnit.SECONDS));
    } else if (scenario.equals("during-start")) {
      Thread.sleep(25);
    } else if (scenario.equals("deadline-before-first")) {
      try { task.completion().toCompletableFuture().get(2, TimeUnit.SECONDS); }
      catch (Exception error) { row.put("deadlineResult", error.toString()); }
    }
    row.put("framesBeforeClose", frames.get());
    long closeBegan = System.nanoTime();
    task.close();
    closedAt.set(System.nanoTime());
    row.put("localCloseMs", (closedAt.get() - closeBegan) / 1_000_000.0);
    if (scenario.equals("before-start")) { task.start(); }
    try {
      task.completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
      row.put("completion", "success-before-cancel");
    } catch (Exception error) {
      row.put("completion", error.getClass().getSimpleName());
      row.put("completionCause", error.getCause() == null ? null : error.getCause().getClass().getSimpleName());
    }
    if (starter != null) { starter.join(3000); }
    row.put("startWorkerFinished", starter == null || !starter.isAlive());
    var immediate = client.tap.snapshot();
    Thread.sleep(1000);
    row.put("lateProductFramesAfterClose", late.get());
    row.put("framesTotal", frames.get());
    row.put("observationWindowAfterCloseMs", 1000);
    row.put("immediateSdkObservation", immediate);
    row.put("sdkObservation", client.tap.snapshot());
    row.put("totalMs", (System.nanoTime() - began) / 1_000_000.0);
    row.put("usageMissingMeansUnknown", true);
    row.put("remoteCancellationAcknowledgementObserved", false);
    return row;
  }

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    String key = System.getenv("REHEVO_TTS_EXPERIMENT_API_KEY");
    if (key == null || key.isBlank()) { throw new IllegalStateException("Existing credential unavailable"); }
    Constants.baseWebsocketApiUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    var properties = new VoiceInterviewProperties();
    var tts = properties.getQwen().getTts();
    tts.setApiKey(key); tts.setModel("qwen-audio-3.1-tts-flash"); tts.setVoice("longanhuan_v3.1");
    tts.setFormat("pcm"); tts.setSampleRate(24000); tts.setVolume(60); tts.setLanguageType("zh");
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("scope", "real component cancellation lifecycle diagnostics; no percentile or remote acknowledgement claim");
    report.put("model", tts.getModel()); report.put("voice", tts.getVoice()); report.put("text", TEXT);
    List<Map<String, Object>> rows = new ArrayList<>(); report.put("results", rows);
    for (String scenario : List.of("before-start", "during-start", "after-first", "deadline-before-first")) {
      var row = invoke(scenario, properties); rows.add(row); write(output, report);
      System.out.println("scenario=" + scenario + " frames=" + row.get("framesTotal") + " late="
          + row.get("lateProductFramesAfterClose") + " closeMs=" + row.get("localCloseMs")
          + " startWorkerFinished=" + row.get("startWorkerFinished"));
    }
    report.put("finishedAt", Instant.now().toString()); write(output, report); System.exit(0);
  }
}
