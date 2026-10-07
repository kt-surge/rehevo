package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.config.VectorTaskRecoveryProperties;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.model.VectorTaskEntity;
import interview.guide.modules.knowledgebase.service.VectorTaskRecoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

@DisplayName("真实入队后退出与定时扫描通知门")
class VectorTaskNotificationIntegrationTest extends VectorizeFaultIntegrationTest {
  @Autowired private ApplicationContext context;
  @Autowired private VectorTaskRecoveryService recovery;
  @Autowired private VectorTaskRecoveryProperties properties;

  @Test
  @DisplayName("实际 XADD 后 halt(75)，重通知保持版本且单消费者只执行一次")
  void notificationCrashAfterXaddPreservesAcceptedVersion() throws Exception {
    Path artifacts = artifacts("notification-child");
    String classpath = Files.readString(Path.of(System.getProperty("rehevo.fault.classpath-file")));
    Path argumentFile = Files.createTempFile("rehevo-notification-crash-", ".args");
    Files.writeString(argumentFile, "-Dfile.encoding=UTF-8\n-cp\n\"" + classpath.replace('\\', '/') + "\"\n"
        + VectorTaskNotificationCrashWorker.class.getName() + "\n" + kbId + "\n\""
        + artifacts.toString().replace('\\', '/') + "\"\n", StandardCharsets.UTF_8);
    Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
        "@" + argumentFile).redirectErrorStream(true).redirectOutput(artifacts.resolve("child.log").toFile()).start();
    try {
      assertThat(child.waitFor(45, TimeUnit.SECONDS)).isTrue();
      assertThat(child.exitValue()).as("须真正到达实际 XADD 后的退出点").isEqualTo(75);
    } finally {
      if (child.isAlive()) { child.destroyForcibly(); assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
      Files.deleteIfExists(argumentFile);
    }
    var checkpoint = mapper.readTree(Files.readString(artifacts.resolve("checkpoint.json")));
    assertThat(checkpoint.path("childPid").asLong()).isEqualTo(child.pid());
    assertThat(checkpoint.path("actualXaddCalled").asBoolean()).isTrue();
    assertThat(checkpoint.path("transactionActiveAtXadd").asBoolean()).isFalse();
    generation = checkpoint.path("generation").asText();
    var accepted = durableTasks.findById(generation).orElseThrow();
    assertThat(accepted.getLastMessageId()).isNull();
    assertThat(accepted.getDeliveryAttempts()).isZero();
    assertThat(ownMessages()).hasSize(1);
    assertThat(recovery.recoverOnce()).isEqualTo(1);
    assertThat(ownMessages()).hasSize(2).allSatisfy(message -> {
      assertThat(message.get("generation")).isEqualTo(generation);
      assertThat(message.get("content")).isEqualTo(VectorTaskNotificationCrashWorker.CONTENT);
    });
    AtomicInteger calls = recordCalls();
    startConsumer();
    observeCompletion();
    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    while (redis.streamGroupMetrics(stream(), group()).backlog() != 0 && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    stopConsumer();
    assertThat(calls.get()).isEqualTo(1);
    assertThat(redis.streamGroupMetrics(stream(), group()).pending()).isZero();
    assertThat(redis.streamGroupMetrics(stream(), group()).backlog()).isZero();
    assertThat(durableTasks.findById(generation).orElseThrow().getState()).isEqualTo(VectorTaskEntity.State.COMPLETED);
    assertThat(jdbc.queryForList("SELECT content FROM vector_store WHERE metadata->>'kb_id'=?",
        String.class, kbId.toString())).containsExactly(VectorTaskNotificationCrashWorker.CONTENT);
    Files.write(artifacts.resolve("recovery.json"), mapper.writeValueAsBytes(Map.of(
        "childExitCode", 75, "physicalNotifications", 2, "sameGenerationAndContent", true,
        "embeddingFixtureCalls", calls.get(), "singleConsumerOnly", true,
        "statusAfterRestart", "COMPLETED", "pendingAfterRestart", 0, "backlogAfterRestart", 0)));
  }

  @Test
  @DisplayName("实际 Scheduled 组件从未入队快照自动投递，不手动调用 recoverOnce")
  void notificationActualScheduledRecoveryDispatchesAcceptedTask() throws Exception {
    Path artifacts = artifacts("scheduled-child");
    long originalDelay = properties.getRedeliveryDelayMs();
    properties.setRedeliveryDelayMs(30000);
    try {
      String content = "controlled actual scheduling fixture: no explicit recovery invocation.";
      generation = tasks.begin(kbId, tasks.prepareInput(content));
      assertThat(ownMessages()).isEmpty();
      AtomicInteger calls = recordCalls();
      startConsumer();
      try (var scheduler = new AnnotationConfigApplicationContext()) {
        scheduler.getEnvironment().getPropertySources().addFirst(new MapPropertySource("controlled-scan",
            Map.of("app.ai.rag.vector-task.scan-delay-ms", "100")));
        scheduler.register(SchedulingConfig.class);
        scheduler.registerBean(VectorTaskRecoveryService.class,
            () -> context.getBean(VectorTaskRecoveryService.class));
        scheduler.refresh();
        observeCompletion();
      }
      stopConsumer();
      assertThat(calls.get()).isEqualTo(1);
      assertThat(ownMessages()).hasSize(1);
      assertThat(durableTasks.findById(generation).orElseThrow().getState()).isEqualTo(VectorTaskEntity.State.COMPLETED);
      Files.write(artifacts.resolve("recovery.json"), mapper.writeValueAsBytes(Map.of(
          "actualScheduledAnnotationActivated", true, "manualRecoverOnceCalled", false,
          "testScanDelayMs", 100, "redeliveryDelayMs", 30000, "embeddingFixtureCalls", calls.get(),
          "physicalNotifications", 1, "status", "COMPLETED", "productionRestartOrLatencyClaim", false)));
    } finally {
      properties.setRedeliveryDelayMs(originalDelay);
    }
  }

  private AtomicInteger recordCalls() {
    AtomicInteger calls = new AtomicInteger();
    doAnswer(invocation -> { calls.incrementAndGet(); writeFixtureVectors(invocation.getArgument(0)); return null; })
        .when(store).add(anyList());
    return calls;
  }

  private void startConsumer() {
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks);
    consumer.init();
  }

  private void observeCompletion() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    while (knowledgeBases.findById(kbId).orElseThrow().getVectorStatus() != VectorStatus.COMPLETED
        && System.nanoTime() < deadline) { Thread.sleep(25); }
    assertThat(knowledgeBases.findById(kbId).orElseThrow().getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
  }

  private List<Map<String, String>> ownMessages() {
    RStream<String, String> messages = redisClient.getStream(stream(), StringCodec.INSTANCE);
    return messages.range(StreamMessageId.MIN, StreamMessageId.MAX).values().stream()
        .filter(fields -> kbId.toString().equals(fields.get("kbId"))).toList();
  }

  private Path artifacts(String name) throws Exception {
    Path path = Path.of(System.getProperty("rehevo.fault.artifacts")).toAbsolutePath().normalize().resolve(name);
    Files.createDirectories(path.getParent());
    Files.createDirectory(path);
    return path;
  }

  private static String stream() { return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY; }
  private static String group() { return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME; }

  @Configuration(proxyBeanMethods = false)
  @EnableScheduling
  static class SchedulingConfig {}
}
