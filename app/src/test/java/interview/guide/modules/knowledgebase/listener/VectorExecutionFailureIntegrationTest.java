package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.config.VectorTaskRecoveryProperties;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.model.VectorTaskEntity;
import interview.guide.modules.knowledgebase.service.VectorTaskExecutionHeartbeat;
import interview.guide.modules.knowledgebase.service.VectorTaskRecoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

@DisplayName("真实计算中退出与心跳数据库故障门")
class VectorExecutionFailureIntegrationTest extends VectorizeFaultIntegrationTest {
  @Autowired private VectorTaskExecutionHeartbeat heartbeat;
  @Autowired private VectorTaskRecoveryProperties properties;
  @Autowired private VectorTaskRecoveryService recovery;

  @Test
  @DisplayName("计算中真实 halt 后恢复沿用预算并清理已退出执行的临时块")
  void executionCrashDuringExternalCallKeepsBudgetAndCleansAbandonedBatch() throws Exception {
    String content = "controlled external execution crash fixture: recover the same accepted body and attempt budget.";
    generation = tasks.begin(kbId, tasks.prepareInput(content));
    String originalMessage = enqueue(content);
    Path artifacts = directory("execution-crash-child");
    String classpath = Files.readString(Path.of(System.getProperty("rehevo.fault.classpath-file")));
    Path argumentFile = Files.createTempFile("rehevo-execution-crash-", ".args");
    Files.writeString(argumentFile, "-Dfile.encoding=UTF-8\n-cp\n\"" + classpath.replace('\\', '/') + "\"\n"
        + VectorExecutionCrashWorker.class.getName() + "\n" + kbId + "\n" + generation + "\n\""
        + artifacts.toString().replace('\\', '/') + "\"\n", StandardCharsets.UTF_8);
    Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
        "@" + argumentFile).redirectErrorStream(true).redirectOutput(artifacts.resolve("child.log").toFile()).start();
    try {
      assertThat(child.waitFor(45, TimeUnit.SECONDS)).isTrue();
      assertThat(child.exitValue()).as("不能把启动失败当作实际计算中退出").isEqualTo(76);
    } finally {
      if (child.isAlive()) { child.destroyForcibly(); assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
      Files.deleteIfExists(argumentFile);
    }
    var checkpoint = mapper.readTree(Files.readString(artifacts.resolve("checkpoint.json")));
    assertThat(checkpoint.path("childPid").asLong()).isEqualTo(child.pid());
    assertThat(checkpoint.path("phase").asText()).isEqualTo("INSIDE_VECTORSTORE_AFTER_TEMPORARY_BATCH");
    assertThat(status()).isEqualTo("PROCESSING");
    assertThat(durableTasks.findById(generation).orElseThrow().getExecutionAttempts()).isEqualTo(1);
    assertThat(temporaryCount()).isEqualTo(1);
    assertThat(vectors.countByKnowledgeBaseId(kbId)).isZero();
    assertThat(redis.streamGroupMetrics(stream(), group()).pending()).isEqualTo(1);
    expireAndMakeDue();
    // Advance only our actual Pending message's idle; this is not measured recovery latency.
    redisClient.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
        "return redis.call('XCLAIM',KEYS[1],ARGV[1],ARGV[2],0,ARGV[3],'IDLE',600000,'JUSTID')",
        RScript.ReturnType.LIST, List.of(stream()), group(), "controlled-crash-idle", originalMessage);
    assertThat(recovery.recoverOnce()).isEqualTo(1);
    AtomicInteger calls = recordCalls();
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks, heartbeat);
    consumer.init();
    await(() -> status().equals("COMPLETED") && redis.streamGroupMetrics(stream(), group()).pending() == 0
        && redis.streamGroupMetrics(stream(), group()).backlog() == 0);
    stopConsumer();
    var completed = durableTasks.findById(generation).orElseThrow();
    var ids = jdbc.queryForList("SELECT id::text FROM vector_store WHERE metadata->>'kb_id'=?", String.class, kbId.toString());
    int remainingTemporary = temporaryCount();
    Map<String, Object> value = Map.of("actualChildExitCode", child.exitValue(), "sameGeneration", completed.getGeneration().equals(generation),
        "persistedAttemptsAfterRestart", completed.getExecutionAttempts(), "persistedFenceAfterRestart", completed.getExecutionFence(),
        "restartFixtureCalls", calls.get(), "temporaryVectorsAfterRecovery", remainingTemporary,
        "originalPendingAfterRecovery", redis.streamGroupMetrics(stream(), group()).pending(),
        "fixtureLeaseAndIdleAdvanced", true, "manualRecoveryCalled", true, "supplierExactlyOnceClaim", false);
    Files.write(artifacts.resolve("recovery.json"), mapper.writeValueAsBytes(value));
    System.out.println("EXECUTION_FAILURE_CRASH " + value);
    assertThat(completed.getState()).isEqualTo(VectorTaskEntity.State.COMPLETED);
    assertThat(completed.getExecutionAttempts()).isEqualTo(2);
    assertThat(completed.getExecutionFence()).isEqualTo(2);
    assertThat(calls.get()).isEqualTo(1);
    assertThat(ids).hasSize(1).doesNotContain(checkpoint.path("temporaryVectorId").asText());
    assertThat(remainingTemporary).as("计算中退出的旧临时批次不得永久残留").isZero();
  }

  @Test
  @DisplayName("真实数据库拒绝心跳后本地撤权，当前批次不能提升且请求可恢复")
  void executionHeartbeatDatabaseErrorRevokesAndRecovers() throws Exception {
    long oldDelay = properties.getHeartbeatDelayMs();
    properties.setHeartbeatDelayMs(1000);
    String ownTrigger = "rehevo_execution_" + kbId;
    String content = "controlled heartbeat database error fixture";
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    FutureTask<Boolean> work = null;
    Thread worker = null;
    try {
      generation = tasks.begin(kbId, tasks.prepareInput(content));
      var execution = tasks.claimExecution(kbId, generation, content).execution();
      jdbc.execute("CREATE FUNCTION " + ownTrigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
          + "IF NEW.generation='" + generation + "' AND OLD.execution_owner IS NOT NULL "
          + "AND NEW.execution_owner=OLD.execution_owner AND NEW.execution_fence=OLD.execution_fence "
          + "AND NEW.execution_lease_until>OLD.execution_lease_until THEN "
          + "RAISE EXCEPTION 'controlled owned heartbeat renewal failure'; END IF; RETURN NEW; END $$");
      jdbc.execute("CREATE TRIGGER " + ownTrigger + " BEFORE UPDATE ON kb_vector_tasks "
          + "FOR EACH ROW EXECUTE FUNCTION " + ownTrigger + "()");
      doAnswer(invocation -> {
        writeFixtureVectors(invocation.getArgument(0));
        entered.countDown();
        assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
        return null;
      }).when(store).add(anyList());
      try (var guard = heartbeat.monitor(execution)) {
        work = new FutureTask<>(() -> service.vectorizeAndStore(kbId, content, generation,
            new DocumentChunkingProperties(), execution, guard::valid));
        worker = new Thread(work, "controlled-heartbeat-external-fixture");
        worker.setDaemon(true);
        worker.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        await(() -> !guard.valid());
        assertThat(tasks.executionOwned(execution)).as("本地失权时数据库租约仍有效").isTrue();
        release.countDown();
        assertThat(work.get(10, TimeUnit.SECONDS)).isFalse();
      }
      assertThat(temporaryCount()).isZero();
      assertThat(vectors.countByKnowledgeBaseId(kbId)).isZero();
      assertThat(status()).isEqualTo("PROCESSING");
      jdbc.execute("DROP TRIGGER " + ownTrigger + " ON kb_vector_tasks");
      jdbc.execute("DROP FUNCTION " + ownTrigger + "()");
      expireAndMakeDue();
      AtomicInteger calls = recordCalls();
      assertThat(recovery.recoverOnce()).isEqualTo(1);
      consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks, heartbeat);
      consumer.init();
      await(() -> status().equals("COMPLETED") && redis.streamGroupMetrics(stream(), group()).pending() == 0);
      stopConsumer();
      assertThat(durableTasks.findById(generation).orElseThrow().getExecutionAttempts()).isEqualTo(2);
      assertThat(calls.get()).isEqualTo(1);
      assertThat(temporaryCount()).isZero();
      Path artifacts = directory("heartbeat-error-child");
      Files.write(artifacts.resolve("recovery.json"), mapper.writeValueAsBytes(Map.of(
          "actualDatabaseRenewalRejected", true, "actualScheduledHeartbeatRevokedLocalGuard", true,
          "databaseLeaseStillValidAtRevocation", true, "revokedExecutionPromotionRejected", true,
          "temporaryVectorsAfterRecovery", 0, "persistedAttemptsAfterRecovery", 2,
          "restartFixtureCalls", calls.get(), "manualRecoveryCalled", true, "fixtureLeaseAdvanced", true)));
    } finally {
      release.countDown();
      if (worker != null && worker.isAlive()) { if (work != null) { work.cancel(true); } worker.join(5000); }
      jdbc.execute("DROP TRIGGER IF EXISTS " + ownTrigger + " ON kb_vector_tasks");
      jdbc.execute("DROP FUNCTION IF EXISTS " + ownTrigger + "()");
      properties.setHeartbeatDelayMs(oldDelay);
    }
  }

  private AtomicInteger recordCalls() {
    AtomicInteger calls = new AtomicInteger();
    doAnswer(invocation -> { calls.incrementAndGet(); writeFixtureVectors(invocation.getArgument(0)); return null; })
        .when(store).add(anyList());
    return calls;
  }
  private void expireAndMakeDue() {
    jdbc.update("UPDATE kb_vector_tasks SET execution_lease_until=clock_timestamp()-interval '1 second',"
        + "next_delivery_at=clock_timestamp()-interval '1 second' WHERE generation=?", generation);
  }
  private int temporaryCount() { return jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id'=?", Integer.class, kbId.toString()); }
  private String status() { return jdbc.queryForObject("SELECT vector_status FROM knowledge_bases WHERE id=?", String.class, kbId); }
  private String enqueue(String content) { return redis.streamAdd(stream(), Map.of("kbId", kbId.toString(), "content", content, "generation", generation, "retryCount", "0")); }
  private Path directory(String suffix) throws Exception {
    Path path = Path.of(System.getProperty("rehevo.fault.artifacts")).toAbsolutePath().normalize().resolve(suffix);
    Files.createDirectories(path);
    return path;
  }
  private static String stream() { return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY; }
  private static String group() { return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME; }
}
