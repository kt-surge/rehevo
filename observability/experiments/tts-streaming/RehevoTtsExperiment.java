import com.alibaba.dashscope.audio.tts.SpeechSynthesisResult;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.alibaba.dashscope.utils.Constants;
import com.google.gson.GsonBuilder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** 独立的供应商组件实验，不启动 Spring，不把首帧等同浏览器起播。 */
public class RehevoTtsExperiment {

  private static String model = "qwen-audio-3.1-tts-flash";
  private static String voice = "longanhuan_v3.1";
  private static final String ENDPOINT = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
  private static final List<String> TEXTS = List.of(
      "请介绍你在这个项目中负责的部分，并说明一次你解决技术问题的过程。",
      "你刚才提到了混合检索，请说明向量召回和词法召回如何融合，以及怎样确认优化没有遗漏必要证据。");

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    int pairs = Math.max(1, Math.min(10, Integer.parseInt(args[1])));
    if (args.length > 2) {
      model = args[2];
    }
    if (args.length > 3) {
      voice = args[3];
    }
    String key = System.getenv("REHEVO_TTS_EXPERIMENT_API_KEY");
    if (key == null || key.isBlank()) {
      throw new IllegalStateException("Missing experiment credential");
    }
    Constants.baseWebsocketApiUrl = ENDPOINT;
    Files.createDirectories(output);
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("createdAt", Instant.now().toString());
    manifest.put("kind", "real-provider-component-diagnostic");
    manifest.put("sdkVersion", "2.22.7");
    manifest.put("model", model);
    manifest.put("modelVersionKind", "alias-not-immutable-snapshot");
    manifest.put("voice", voice);
    manifest.put("format", "pcm-s16le-mono-24000");
    manifest.put("speechRate", 1.0);
    manifest.put("volume", 60);
    manifest.put("languageHints", List.of("zh"));
    manifest.put("seed", 0);
    manifest.put("endpoint", ENDPOINT);
    manifest.put("configurationSource", "explicit experiment parameters; not running application settings");
    manifest.put("pairsRequested", pairs);
    manifest.put("warmup", "none; fresh synthesizer for every request; orders alternate AB/BA");
    manifest.put("firstDeliveredPcmMsDefinition",
        "invocation to first non-empty PCM exposed to caller; A exposes complete audio");
    manifest.put("limitations", "component only; no browser, no ASR/LLM, small sample, no P95 claim");
    List<Map<String, Object>> results = new ArrayList<>();
    manifest.put("results", results);
    for (int pair = 0; pair < pairs; pair++) {
      String text = TEXTS.get(pair % TEXTS.size());
      List<String> order = pair % 2 == 0 ? List.of("whole", "frames") : List.of("frames", "whole");
      for (String mode : order) {
        Map<String, Object> result = invoke(pair, mode, text, key);
        results.add(result);
        write(output, manifest);
        System.out.println("pair=" + pair + " mode=" + mode + " status=" + result.get("status")
            + " bytes=" + result.get("bytes") + " firstDeliveredPcmMs=" + result.get("firstDeliveredPcmMs"));
        if (!"success".equals(result.get("status"))) {
          manifest.put("stoppedEarly", true);
          write(output, manifest);
          System.out.println("Stopped after provider failure; inspect results.json for sanitized diagnosis.");
          System.exit(2);
          return;
        }
      }
    }
    manifest.put("stoppedEarly", false);
    write(output, manifest);
    System.exit(0);
  }

  private static Map<String, Object> invoke(int pair, String mode, String text, String key)
      throws Exception {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("pair", pair);
    result.put("mode", mode);
    result.put("text", text);
    result.put("textSha256", sha256(text.getBytes(StandardCharsets.UTF_8)));
    result.put("startedAt", Instant.now().toString());
    SpeechSynthesisParam params = SpeechSynthesisParam.builder()
        .model(model).voice(voice).apiKey(key)
        .format(SpeechSynthesisAudioFormat.PCM_24000HZ_MONO_16BIT)
        .speechRate(1.0f).volume(60).languageHints(List.of("zh"))
        .parameter("seed", 0).build();
    SpeechSynthesizer synthesizer = new SpeechSynthesizer(params, null);
    FrameStats stats = new FrameStats();
    long started = System.nanoTime();
    try {
      if ("whole".equals(mode)) {
        stats.accept(synthesizer.call(text, 30_000L), started);
      } else {
        synthesizer.callAsFlowable(text).timeout(30, TimeUnit.SECONDS)
            .blockingForEach((SpeechSynthesisResult frame) -> stats.accept(frame.getAudioFrame(), started));
      }
      result.put("status", stats.bytes > 0 && stats.bytes % 2 == 0 ? "success" : "invalid-audio");
    } catch (Exception error) {
      result.put("status", "provider-error");
      result.put("errorType", error.getClass().getSimpleName());
      String message = String.valueOf(error.getMessage()).replace(key, "[REDACTED]")
          .replaceAll("(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]+", "[REDACTED]");
      result.put("errorMessage", message.substring(0, Math.min(1200, message.length())));
    } finally {
      result.put("totalMs", millis(System.nanoTime() - started));
      result.put("firstDeliveredPcmMs", stats.firstNanos == 0 ? null : millis(stats.firstNanos - started));
      result.put("bytes", stats.bytes);
      result.put("framesExposed", stats.frames);
      result.put("pcmDurationMs", stats.bytes / 48.0);
      result.put("audioSha256", HexFormat.of().formatHex(stats.digest.digest()));
      result.put("sdkFirstPackageDelayMs", synthesizer.getFirstPackageDelay());
      try {
        result.put("connectionClosed", synthesizer.getDuplexApi().close(1000, "experiment-complete"));
      } catch (Exception closeError) {
        result.put("connectionClosed", false);
        result.put("closeErrorType", closeError.getClass().getSimpleName());
      }
    }
    return result;
  }

  private static void write(Path output, Map<String, Object> manifest) throws Exception {
    Files.writeString(output.resolve("results.json"),
        new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(manifest),
        StandardCharsets.UTF_8);
  }

  private static double millis(long nanos) {
    return nanos / 1_000_000.0;
  }

  private static String sha256(byte[] data) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
  }

  private static class FrameStats {
    final MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long firstNanos;
    long bytes;
    int frames;

    FrameStats() throws Exception {}

    void accept(ByteBuffer buffer, long started) {
      if (buffer == null || !buffer.hasRemaining()) {
        return;
      }
      if (firstNanos == 0) {
        firstNanos = System.nanoTime();
      }
      ByteBuffer copy = buffer.slice();
      bytes += copy.remaining();
      frames++;
      digest.update(copy);
    }
  }
}
