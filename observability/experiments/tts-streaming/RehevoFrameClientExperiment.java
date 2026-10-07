package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.utils.Constants;
import com.google.gson.GsonBuilder;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 实际生产 TTS 客户端组件实验；没有 LLM、ASR、WebSocket 面试 handler 或浏览器。 */
public class RehevoFrameClientExperiment {
  private record PairPlan(int textIndex, boolean framesFirst) {}
  private static final List<String> TEXTS = List.of(
      "请介绍你负责的项目。",
      "请介绍你在这个项目中负责的部分，并说明一次你解决技术问题的过程。",
      "你刚才提到了混合检索，请说明向量召回和词法召回如何融合，以及怎样确认优化没有遗漏必要证据。",
      "接口的平均响应时间和百分之九十五分位数有什么差别？请结合一次慢请求排查说明。",
      "你的服务如何处理重复消息？如果数据库提交成功，但消息确认前进程退出，会发生什么？",
      "请说明 Java 二十一的虚拟线程适合哪些任务，以及调用外部模型时如何设置超时。",
      "版本从十七点零点十二升级到二十一时，你会检查哪些兼容性问题？",
      "用户在面试官播报过程中点击中断，你会怎样清理音频队列，并避免上一轮的迟到结果继续播放？");

  private static final class ObservedClient extends QwenTtsService {
    SdkUsageTap tap;
    ObservedClient(VoiceInterviewProperties properties) { super(properties); }
    @Override
    SpeechSynthesizer createSynthesizer(SpeechSynthesisParam parameters) {
      var synthesizer = super.createSynthesizer(parameters);
      try {
        tap = SdkUsageTap.install(synthesizer);
        return synthesizer;
      } catch (ReflectiveOperationException error) {
        throw new IllegalStateException("Experiment-only usage tap unavailable", error);
      }
    }
  }

  private static final class Stats {
    final ByteArrayOutputStream audio = new ByteArrayOutputStream();
    final List<Map<String, Object>> frames = new ArrayList<>();
    long firstNanos;
    synchronized void accept(byte[] pcm, long start) {
      if (pcm.length == 0) { return; }
      long now = System.nanoTime();
      if (firstNanos == 0) { firstNanos = now; }
      frames.add(Map.of("index", frames.size(), "bytes", pcm.length, "arrivalMs", millis(now - start)));
      audio.writeBytes(pcm);
    }
  }

  private static double millis(long nanos) { return nanos / 1_000_000.0; }
  private static String sha(byte[] bytes) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
  private static void write(Path path, Object value) throws Exception {
    Files.writeString(path, new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(value),
        StandardCharsets.UTF_8);
  }

  private static Map<String, Object> invoke(Path output, int pair, String mode, String text,
      VoiceInterviewProperties properties, String key) throws Exception {
    var client = new ObservedClient(properties);
    var stats = new Stats();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("pair", pair); row.put("mode", mode); row.put("text", text);
    row.put("textSha256", sha(text.getBytes(StandardCharsets.UTF_8)));
    row.put("startedAt", Instant.now().toString());
    long began = System.nanoTime();
    VoiceTtsTask task = null;
    try {
      if (mode.equals("whole")) {
        stats.accept(client.synthesize(text), began);
      } else {
        task = client.prepareFrames(text, Duration.ofSeconds(12), pcm -> stats.accept(pcm, began));
        task.start();
        row.put("taskSummary", task.completion().toCompletableFuture().get(15, TimeUnit.SECONDS));
      }
      row.put("status", stats.audio.size() > 0 && stats.audio.size() % 2 == 0 ? "success" : "invalid-audio");
    } catch (Exception error) {
      row.put("status", "failed"); row.put("errorType", error.getClass().getSimpleName());
      row.put("errorMessage", String.valueOf(error.getMessage()).replace(key, "[REDACTED]")
          .replaceAll("(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]+", "[REDACTED]"));
    } finally {
      if (task != null) { task.close(); }
      row.put("totalMs", millis(System.nanoTime() - began));
      row.put("firstDeliveredPcmMs", stats.firstNanos == 0 ? null : millis(stats.firstNanos - began));
      byte[] audio = stats.audio.toByteArray();
      String name = String.format("%03d-%s.pcm", pair, mode);
      Files.write(output.resolve(name), audio);
      row.put("audioPath", name); row.put("audioSha256", sha(audio));
      row.put("bytes", audio.length); row.put("pcmDurationMs", audio.length / 48.0);
      row.put("frames", stats.frames);
      row.put("sdkObservation", client.tap == null ? null : client.tap.snapshot());
    }
    return row;
  }

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    int pairs = Integer.parseInt(args[1]);
    int randomSeed = args.length > 2 ? Integer.parseInt(args[2]) : 20261004;
    if (pairs < 1 || pairs > 50) { throw new IllegalArgumentException("Pairs must be 1..50"); }
    String key = System.getenv("REHEVO_TTS_EXPERIMENT_API_KEY");
    if (key == null || key.isBlank()) { throw new IllegalStateException("Missing existing credential"); }
    Constants.baseWebsocketApiUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    var properties = new VoiceInterviewProperties();
    var tts = properties.getQwen().getTts();
    tts.setApiKey(key); tts.setModel("qwen-audio-3.1-tts-flash"); tts.setVoice("longanhuan_v3.1");
    tts.setSampleRate(24000); tts.setFormat("pcm"); tts.setSpeechRate(1.0f);
    tts.setVolume(60); tts.setLanguageType("zh");
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("startedAt", Instant.now().toString());
    report.put("scope", "production QwenTtsService whole versus prepared frames; component only, no browser or LLM");
    report.put("sdkVersion", "2.22.7"); report.put("model", tts.getModel()); report.put("voice", tts.getVoice());
    report.put("format", "pcm-s16le-mono-24000"); report.put("speechRate", 1.0); report.put("volume", 60);
    report.put("language", "zh"); report.put("seed", "not explicitly sent in either arm; documented server default 0");
    report.put("pairsRequested", pairs); report.put("freshClientEveryCall", true);
    var random = new Random(randomSeed);
    List<PairPlan> plan = new ArrayList<>();
    for (int textIndex = 0; textIndex < TEXTS.size(); textIndex++) {
      boolean framesFirst = random.nextBoolean();
      for (int occurrence = textIndex; occurrence < pairs; occurrence += TEXTS.size()) {
        plan.add(new PairPlan(textIndex, framesFirst)); framesFirst = !framesFirst;
      }
    }
    Collections.shuffle(plan, random);
    report.put("pairOrderRandomSeed", randomSeed); report.put("pairPlan", plan);
    report.put("texts", TEXTS);
    report.put("alternatingOrder", "seeded shuffled text pairs; balanced AB/BA per text; no retries");
    report.put("usageObservation", "experiment-only raw SDK result observer applied equally to both arms; no production reflection");
    report.put("firstAudioDefinition", "invocation to first non-empty PCM delivered to experiment; not browser playback");
    report.put("limitations", "model alias; synthetic scripted text; component study; listening and content checks separate");
    List<Map<String, Object>> rows = new ArrayList<>(); report.put("results", rows);
    for (int pair = 0; pair < pairs; pair++) {
      PairPlan item = plan.get(pair);
      for (String mode : item.framesFirst() ? List.of("frames", "whole") : List.of("whole", "frames")) {
        var row = invoke(output, pair, mode, TEXTS.get(item.textIndex()), properties, key);
        row.put("processFirstInvocation", rows.isEmpty());
        rows.add(row); write(output.resolve("results.json"), report);
        System.out.println("pair=" + pair + " mode=" + mode + " status=" + row.get("status")
            + " firstDeliveredPcmMs=" + row.get("firstDeliveredPcmMs"));
        if (!row.get("status").equals("success")) {
          report.put("stoppedEarly", true); write(output.resolve("results.json"), report); System.exit(2);
        }
        Thread.sleep(250);
      }
    }
    report.put("finishedAt", Instant.now().toString()); report.put("stoppedEarly", false);
    write(output.resolve("results.json"), report); System.exit(0);
  }
}
