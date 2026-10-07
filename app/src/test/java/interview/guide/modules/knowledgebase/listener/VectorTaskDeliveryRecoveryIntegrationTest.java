package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RStream;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/** Same recovery requirements before/after durable task changes. Models remain deterministic fixtures. */
@DisplayName("真实接受后未入队退出与消息裁剪恢复对照")
class VectorTaskDeliveryRecoveryIntegrationTest extends VectorizeFaultIntegrationTest {
  @Autowired private ApplicationContext context;
  private String ownStream;
  private Path ownArtifacts;

  @AfterEach
  void deleteOwnStream() {
    if (ownStream != null) { redisClient.getKeys().delete(ownStream); }
  }

  @Test
  @DisplayName("实际接受提交后 XADD 前退出，重启须凭持久化任务恢复")
  void deliveryCrashBeforeXaddMustRecoverAcceptedRequest() throws Exception {
    ownArtifacts = artifacts("enqueue-child");
    String classpath = Files.readString(Path.of(System.getProperty("rehevo.fault.classpath-file")));
    Path argumentFile = Files.createTempFile("rehevo-enqueue-crash-", ".args");
    Files.writeString(argumentFile, "-Dfile.encoding=UTF-8\n-cp\n\"" + classpath.replace('\\', '/') + "\"\n"
        + VectorTaskEnqueueCrashWorker.class.getName() + "\n" + kbId + "\n\""
        + ownArtifacts.toString().replace('\\', '/') + "\"\n", StandardCharsets.UTF_8);
    Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
        "@" + argumentFile).redirectErrorStream(true).redirectOutput(ownArtifacts.resolve("child.log").toFile()).start();
    try {
      assertThat(child.waitFor(45, TimeUnit.SECONDS)).isTrue();
      assertThat(child.exitValue()).as("真正的接受后 halt(74)，不能以启动失败代替").isEqualTo(74);
    } finally {
      if (child.isAlive()) { child.destroyForcibly(); assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
      Files.deleteIfExists(argumentFile);
    }
    var checkpoint = mapper.readTree(Files.readString(ownArtifacts.resolve("checkpoint.json")));
    assertThat(checkpoint.path("childPid").asLong()).isEqualTo(child.pid());
    assertThat(checkpoint.path("actualXaddCalled").asBoolean()).isFalse();
    assertThat(checkpoint.path("phase").asText()).isEqualTo("ACCEPTED_BEFORE_XADD");
    var afterCrash = knowledgeBases.findById(kbId).orElseThrow();
    assertThat(afterCrash.getVectorStatus()).isEqualTo(VectorStatus.PENDING);
    assertThat(afterCrash.getVectorGeneration()).isEqualTo(checkpoint.path("generation").asText());
    assertThat(ownMessageCount()).isZero();
    assertThat(redis.streamGroupMetrics(stream(), group()).pending()).isZero();
    AtomicInteger calls = recordEmbeddingCalls();
    startConsumer();
    boolean recoveryAvailable = recoverIfAvailable();
    observeUntilComplete();
    stopConsumer();
    var finalKb = knowledgeBases.findById(kbId).orElseThrow();
    var observations = Map.of("childExitCode", child.exitValue(), "statusAfterCrash", "PENDING",
        "ownMessagesBeforeRestart", 0, "pendingBeforeRestart", 0, "recoveryAvailable", recoveryAvailable,
        "statusAfterRestart", finalKb.getVectorStatus().name(), "embeddingFixtureCalls", calls.get(),
        "formalVectors", vectors.countByKnowledgeBaseId(kbId), "ownMessagesAfterRestart", ownMessageCount());
    Files.write(ownArtifacts.resolve("recovery.json"), mapper.writeValueAsBytes(observations));
    System.out.println("DELIVERY_ENQUEUE_GAP " + observations);
    assertThat(finalKb.getVectorStatus()).as("接受的请求应在重启后自动恢复").isEqualTo(VectorStatus.COMPLETED);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT content FROM vector_store WHERE metadata->>'kb_id'=?",
        String.class, kbId.toString())).containsExactly(VectorTaskEnqueueCrashWorker.CONTENT);
  }

  @Test
  @DisplayName("实际 MAXLEN 裁剪 Pending 正文后，须凭任务快照恢复")
  void deliveryTrimmedNotificationMustRecoverOriginalContent() throws Exception {
    ownArtifacts = artifacts("trim-child");
    ownStream = "rehevo:delivery-fixture:" + UUID.randomUUID();
    String ownGroup = "delivery-fixture-group";
    redis.createStreamGroup(ownStream, ownGroup);
    var producer = new VectorizeStreamProducer(redis, new ApplicationMetrics(null), tasks) {
      @Override protected String streamKey() { return ownStream; }
    };
    String content = "controlled trim recovery fixture: durable body must retain the accepted original content.";
    producer.sendVectorizeTask(kbId, content);
    RStream<String, String> isolated = redisClient.getStream(ownStream, StringCodec.INSTANCE);
    var original = isolated.readGroup(ownGroup, "original-owner", StreamReadGroupArgs.neverDelivered().count(1));
    assertThat(original).hasSize(1);
    StreamMessageId originalId = original.keySet().iterator().next();
    assertThat(redis.streamGroupMetrics(ownStream, ownGroup).pending()).isEqualTo(1);
    Map<String, String> filler = new HashMap<>(original.values().iterator().next());
    filler.put("content", "discardable isolated trim filler");
    for (int index = 0; index < 1600; index++) {
      redis.streamAdd(ownStream, filler, AsyncTaskStreamConstants.STREAM_MAX_LEN);
    }
    assertThat(isolated.range(originalId, originalId)).isEmpty();
    // Only the disposable stream uses min-idle 0; this is not production recovery latency.
    var reclaimed = isolated.autoClaim(ownGroup, "claim-owner", 0, TimeUnit.MILLISECONDS, StreamMessageId.MIN, 10);
    assertThat(reclaimed.getDeletedIds()).contains(originalId);
    assertThat(reclaimed.getMessages()).doesNotContainKey(originalId);
    AtomicInteger calls = recordEmbeddingCalls();
    startConsumer();
    boolean recoveryAvailable = recoverIfAvailable();
    observeUntilComplete();
    stopConsumer();
    var observations = Map.of("originalId", originalId.toString(), "maxLen", AsyncTaskStreamConstants.STREAM_MAX_LEN,
        "fillerMessages", 1600, "originalBodyTrimmed", true, "deletedIdReturnedByActualClaim", true,
        "recoveryAvailable", recoveryAvailable, "statusAfterRecovery", knowledgeBases.findById(kbId).orElseThrow().getVectorStatus().name(),
        "embeddingFixtureCalls", calls.get(), "formalVectors", vectors.countByKnowledgeBaseId(kbId));
    Files.write(ownArtifacts.resolve("recovery.json"), mapper.writeValueAsBytes(observations));
    System.out.println("DELIVERY_TRIM_GAP " + observations);
    assertThat(knowledgeBases.findById(kbId).orElseThrow().getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT content FROM vector_store WHERE metadata->>'kb_id'=?",
        String.class, kbId.toString())).containsExactly(content);
  }

  private AtomicInteger recordEmbeddingCalls() {
    AtomicInteger calls = new AtomicInteger();
    doAnswer(invocation -> { calls.incrementAndGet(); writeFixtureVectors(invocation.getArgument(0)); return null; })
        .when(store).add(anyList());
    return calls;
  }

  private void startConsumer() {
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks);
    consumer.init();
  }

  private boolean recoverIfAvailable() throws Exception {
    // Before the candidate class exists there is no recovery implementation; never substitute a fixture sender.
    try {
      Class<?> type = Class.forName("interview.guide.modules.knowledgebase.service.VectorTaskRecoveryService");
      type.getMethod("recoverOnce").invoke(context.getBean(type));
      return true;
    } catch (ClassNotFoundException absentInBaseline) {
      return false;
    }
  }

  private void observeUntilComplete() throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    while (knowledgeBases.findById(kbId).orElseThrow().getVectorStatus() != VectorStatus.COMPLETED
        && System.nanoTime() < deadline) { Thread.sleep(25); }
  }

  private long ownMessageCount() {
    RStream<String, String> messages = redisClient.getStream(stream(), StringCodec.INSTANCE);
    return messages.range(StreamMessageId.MIN, StreamMessageId.MAX).values().stream()
        .filter(fields -> kbId.toString().equals(fields.get("kbId"))).count();
  }

  private Path artifacts(String suffix) throws Exception {
    Path base = Path.of(System.getProperty("rehevo.fault.artifacts")).toAbsolutePath().normalize();
    Files.createDirectories(base);
    Path path = base.resolve(suffix);
    Files.createDirectory(path);
    return path;
  }

  private static String stream() { return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY; }
  private static String group() { return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME; }
}
