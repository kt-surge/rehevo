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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 真供应商 + 生产有序管线；不是 WebSocket 或浏览器验收。 */
public class RehevoOrderedFrameExperiment {
  private static final List<String> TEXTS = List.of("请介绍你负责的项目。",
      "请介绍你在这个项目中负责的部分，并说明一次你解决技术问题的过程。");
  private record Observation(String text, SdkUsageTap tap) { }
  private static final class Client extends QwenTtsService {
    final List<Observation> observations = new CopyOnWriteArrayList<>();
    SdkUsageTap latest;
    Client(VoiceInterviewProperties properties) { super(properties); }
    @Override SpeechSynthesizer createSynthesizer(SpeechSynthesisParam parameters) {
      var synthesizer = super.createSynthesizer(parameters);
      try { latest = SdkUsageTap.install(synthesizer); }
      catch (ReflectiveOperationException error) { throw new IllegalStateException("Usage tap unavailable", error); }
      return synthesizer;
    }
    @Override public synchronized VoiceTtsTask prepareFrames(String text, Duration timeout, Consumer<byte[]> callback) {
      VoiceTtsTask task = super.prepareFrames(text, timeout, callback);
      observations.add(new Observation(text, latest)); return task;
    }
    List<Map<String, Object>> snapshot() {
      return observations.stream().map(o -> Map.<String, Object>of("text", o.text(), "sdk", o.tap().snapshot())).toList();
    }
  }

  private static Map<String, Object> invoke(Path output, String scenario, VoiceInterviewProperties properties) throws Exception {
    var client = new Client(properties);
    var first = new CountDownLatch(1);
    var events = new CopyOnWriteArrayList<Map<String, Object>>();
    var audio = List.of(new ByteArrayOutputStream(), new ByteArrayOutputStream());
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("scenario", scenario); row.put("startedAt", Instant.now().toString());
    long began = System.nanoTime();
    var pipeline = new OrderedPcmTtsPipeline(client, task -> Thread.ofVirtual().start(task),
        scenario.equals("normal") ? 2 : 1, Duration.ofSeconds(12), 4,
        scenario.equals("overflow") ? 2 : 720000, frame -> {
          events.add(Map.of("sentenceIndex", frame.sentenceIndex(), "frameIndex", frame.frameIndex(),
              "endOfSentence", frame.endOfSentence(), "bytes", frame.pcm().length,
              "deliveredMs", (System.nanoTime() - began) / 1_000_000.0));
          audio.get(frame.sentenceIndex()).writeBytes(frame.pcm());
          if (frame.pcm().length > 0) { first.countDown(); }
        });
    try {
      TEXTS.forEach(pipeline::submit); pipeline.finish();
      if (scenario.equals("cancel-after-first")) {
        row.put("firstReceivedBeforeCancel", first.await(5, TimeUnit.SECONDS));
        long closeStart = System.nanoTime();
        pipeline.close(); row.put("localCloseMs", (System.nanoTime() - closeStart) / 1_000_000.0);
        row.put("eventsAtClose", events.size());
      }
      try {
        int count = pipeline.completion().toCompletableFuture().get(15, TimeUnit.SECONDS);
        row.put("terminal", "success"); row.put("deliveredFrames", count);
      } catch (Exception error) {
        row.put("terminal", "failed"); row.put("errorType", error.getClass().getSimpleName());
        row.put("cause", error.getCause() == null ? null : error.getCause().getClass().getSimpleName());
      }
    } finally { pipeline.close(); }
    Thread.sleep(1000);
    row.put("events", List.copyOf(events)); row.put("observationAfterTerminalMs", 1000);
    row.put("sdkObservations", client.snapshot());
    row.put("remoteCancellationAcknowledgementObserved", false);
    List<Map<String, Object>> audioFiles = new ArrayList<>();
    for (int sentence = 0; sentence < audio.size(); sentence++) {
      byte[] pcm = audio.get(sentence).toByteArray();
      String filename = scenario + "-sentence-" + sentence + ".pcm";
      Files.write(output.resolve(filename), pcm);
      audioFiles.add(Map.of("sentenceIndex", sentence, "text", TEXTS.get(sentence), "file", filename,
          "bytes", pcm.length, "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pcm))));
    }
    row.put("audio", audioFiles); return row;
  }

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    String key = System.getenv("REHEVO_TTS_EXPERIMENT_API_KEY");
    if (key == null || key.isBlank()) { throw new IllegalStateException("Existing credential unavailable"); }
    Constants.baseWebsocketApiUrl = "wss://dashscope.aliyuncs.com/api-ws/v1/inference";
    var properties = new VoiceInterviewProperties(); var tts = properties.getQwen().getTts();
    tts.setApiKey(key); tts.setModel("qwen-audio-3.1-tts-flash"); tts.setVoice("longanhuan_v3.1");
    tts.setFormat("pcm"); tts.setSampleRate(24000); tts.setVolume(60); tts.setLanguageType("zh");
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("scope", "real provider + production ordered PCM pipeline; no WebSocket/browser performance claim");
    report.put("model", tts.getModel()); report.put("voice", tts.getVoice()); report.put("format", "pcm_s16le-mono-24000");
    var rows = new ArrayList<Map<String, Object>>(); report.put("results", rows);
    for (String scenario : List.of("normal", "cancel-after-first", "overflow")) {
      var row = invoke(output, scenario, properties); rows.add(row);
      Files.writeString(output.resolve("results.json"), new GsonBuilder().setPrettyPrinting().serializeNulls()
          .create().toJson(report), StandardCharsets.UTF_8);
      System.out.println("scenario=" + scenario + " terminal=" + row.get("terminal"));
      if (scenario.equals("normal") != row.get("terminal").equals("success")) { System.exit(2); }
    }
    System.exit(0);
  }
}
