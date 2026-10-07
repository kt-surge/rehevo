package interview.guide.modules.voiceinterview.service;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.google.gson.Gson;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 仅实验 classpath/profile：两组相同的 SDK 数字用量采集，不进入产品。 */
@Service
@Primary
@Profile("voice-browser-study")
public class VoiceBrowserStudyTtsObserver extends QwenTtsService {
  private final AtomicLong sequence = new AtomicLong();
  private final Map<Long, SdkUsageTap> taps = new ConcurrentHashMap<>();
  private final Map<Long, Map<String, Object>> inputs = new ConcurrentHashMap<>();
  private final Map<Long, String> previousSnapshots = new ConcurrentHashMap<>();
  private final ThreadLocal<Map<String, Object>> invocation = new ThreadLocal<>();
  private final Gson json = new Gson();
  private Path output;

  public VoiceBrowserStudyTtsObserver(VoiceInterviewProperties properties) {
    super(properties);
  }

  @PostConstruct
  public void initializeStudyOutput() throws IOException {
    String configured = System.getProperty("rehevo.voice.study.output");
    if (configured == null) {
      throw new IllegalStateException("Study output path is required");
    }
    Path allowed = Path.of("observability/experiments/voice-frame-pipeline/runs")
        .toAbsolutePath().normalize();
    output = Path.of(configured).toAbsolutePath().normalize();
    if (!output.startsWith(allowed) || !output.getFileName().toString().equals("tts-usage.jsonl")) {
      throw new IllegalStateException("Study output must remain inside the new experiment run");
    }
    for (Path ancestor = output.getParent(); ancestor != null && ancestor.startsWith(allowed);
         ancestor = ancestor.getParent()) {
      if (Files.exists(ancestor.resolve("artifacts.sha256.json"))) {
        throw new IllegalStateException("Sealed experiment directories are read-only");
      }
    }
    Files.createDirectories(output.getParent());
    Files.createFile(output);
    append(Map.of("kind", "observer_ready", "at", Instant.now().toString(),
        "questionClassOrigin", interview.guide.modules.voiceinterview.service.DashscopeLlmService.class
            .getProtectionDomain().getCodeSource().getLocation().toString()));
  }

  private Map<String, Object> input(String text, String mode) {
    try {
      return Map.of("mode", mode, "characters", text.length(), "textSha256",
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
              .digest(text.getBytes(StandardCharsets.UTF_8))));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 unavailable", error);
    }
  }

  @Override
  public byte[] synthesize(String text) {
    if (text == null || text.isBlank()) {
      return super.synthesize(text);
    }
    invocation.set(input(text, "whole"));
    try {
      return super.synthesize(text);
    } finally {
      invocation.remove();
      flushStudyUsage();
    }
  }

  @Override
  public synchronized VoiceTtsTask prepareFrames(String text, Duration timeout, Consumer<byte[]> callback) {
    invocation.set(input(text, "frames"));
    try {
      return super.prepareFrames(text, timeout, callback);
    } finally {
      invocation.remove();
    }
  }

  @Override
  SpeechSynthesizer createSynthesizer(SpeechSynthesisParam parameters) {
    var synthesizer = super.createSynthesizer(parameters);
    try {
      var tap = SdkUsageTap.install(synthesizer);
      long id = sequence.incrementAndGet();
      inputs.put(id, invocation.get() == null ? Map.of("mode", "unknown") : invocation.get());
      taps.put(id, tap);
      append(Map.of("kind", "synthesizer_created", "at", Instant.now().toString(),
          "id", id, "input", inputs.get(id)));
      return synthesizer;
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException("Study usage observer failed before provider invocation", error);
    }
  }

  @Scheduled(fixedDelay = 1000)
  public void flushStudyUsage() {
    if (output == null) {
      return;
    }
    taps.forEach((id, tap) -> {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("kind", "usage_snapshot");
      row.put("id", id);
      row.put("input", inputs.get(id));
      row.putAll(tap.snapshot());
      String state = json.toJson(row);
      if (state.equals(previousSnapshots.put(id, state))) {
        return;
      }
      row.put("at", Instant.now().toString());
      append(row);
    });
  }

  @PreDestroy
  public void finalStudySnapshot() {
    flushStudyUsage();
  }

  private synchronized void append(Map<String, Object> row) {
    try {
      Files.writeString(output, json.toJson(row) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    } catch (IOException error) {
      throw new IllegalStateException("Study numeric evidence cannot be persisted", error);
    }
  }
}
