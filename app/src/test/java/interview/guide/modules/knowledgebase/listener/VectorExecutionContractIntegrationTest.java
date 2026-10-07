package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.config.VectorTaskRecoveryProperties;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.model.VectorExecutionClaimDTO;
import interview.guide.modules.knowledgebase.model.VectorTaskExecutionDTO;
import interview.guide.modules.knowledgebase.model.VectorTaskEntity;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import interview.guide.modules.knowledgebase.service.VectorTaskExecutionHeartbeat;
import interview.guide.modules.knowledgebase.service.VectorTaskRecoveryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

@DisplayName("真实执行租约、预算与提交协议门")
class VectorExecutionContractIntegrationTest extends VectorizeFaultIntegrationTest {
  @Autowired private VectorTaskRecoveryProperties properties;
  @Autowired private VectorTaskExecutionHeartbeat heartbeat;
  @Autowired private VectorTaskRecoveryService recovery;
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("实际心跳续约阻止第二认领，过期或旧 fence 不能续约")
  void executionHeartbeatAndExpiredOwnerGuards() throws Exception {
    long oldLease = properties.getExecutionLeaseMs();
    long oldHeartbeat = properties.getHeartbeatDelayMs();
    properties.setExecutionLeaseMs(4000);
    properties.setHeartbeatDelayMs(1000);
    try {
      String content = "controlled heartbeat fixture";
      var first = acceptAndClaim(content);
      Instant initial = durableTasks.findById(generation).orElseThrow().getExecutionLeaseUntil();
      try (var guard = heartbeat.monitor(first)) {
        await(() -> durableTasks.findById(generation).orElseThrow().getExecutionLeaseUntil().isAfter(initial));
        assertThat(guard.valid()).isTrue();
        assertThat(tasks.claimExecution(kbId, generation, content).status()).isEqualTo(VectorExecutionClaimDTO.Status.SKIPPED);
      }
      expireLease();
      assertThat(tasks.renewExecution(first)).as("到期者不能自行续命").isFalse();
      var second = tasks.claimExecution(kbId, generation, content).execution();
      assertThat(second.fence()).isGreaterThan(first.fence());
      assertThat(tasks.renewExecution(first)).isFalse();
      assertThat(tasks.executionOwned(first)).isFalse();
      assertThat(tasks.executionOwned(second)).isTrue();
      observation("heartbeat", Map.of("actualScheduledRenewal", true, "initialLease", initial.toString(),
          "oldFence", first.fence(), "newFence", second.fence(), "oldOwnerRenewRejected", true,
          "fixtureLeaseTimeAdvanced", true, "recoveryLatencyClaim", false));
    } finally {
      properties.setExecutionLeaseMs(oldLease);
      properties.setHeartbeatDelayMs(oldHeartbeat);
    }
  }

  @Test
  @DisplayName("实际通知重建不能重置四次执行预算，耗尽后新通知不调用模型")
  void executionNotificationRebuildCannotResetPersistentBudget() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    doAnswer(invocation -> {
      int current = calls.incrementAndGet();
      assertThat(durableTasks.findById(generation).orElseThrow().getExecutionAttempts()).isEqualTo(current);
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "controlled persistent budget failure");
    }).when(store).add(anyList());
    String content = "controlled persistent budget fixture: all rebuilt notifications keep retryCount zero.";
    generation = tasks.begin(kbId, tasks.prepareInput(content));
    enqueue(content);
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks, heartbeat);
    consumer.init();
    for (int attempt = 1; attempt <= 4; attempt++) {
      int expected = attempt;
      String expectedStatus = attempt == 4 ? "FAILED" : "PENDING";
      await(() -> calls.get() == expected && status().equals(expectedStatus)
          && redis.streamGroupMetrics(stream(), group()).pending() == 0);
      var task = durableTasks.findById(generation).orElseThrow();
      assertThat(task.getExecutionAttempts()).isEqualTo(attempt);
      if (attempt < 4) {
        jdbc.update("UPDATE kb_vector_tasks SET next_delivery_at=clock_timestamp()-interval '1 second' WHERE generation=?", generation);
        assertThat(recovery.recoverOnce()).isEqualTo(1);
      }
    }
    for (int index = 0; index < 3; index++) { enqueue(content); }
    await(() -> redis.streamGroupMetrics(stream(), group()).backlog() == 0
        && redis.streamGroupMetrics(stream(), group()).pending() == 0);
    stopConsumer();
    assertThat(calls.get()).isEqualTo(4);
    assertThat(durableTasks.findById(generation).orElseThrow().getState()).isEqualTo(VectorTaskEntity.State.FAILED);
    var messages = redisClient.<String, String>getStream(stream(), StringCodec.INSTANCE)
        .range(StreamMessageId.MIN, StreamMessageId.MAX).values().stream()
        .filter(row -> kbId.toString().equals(row.get("kbId"))).toList();
    assertThat(messages).hasSize(7).allSatisfy(row -> assertThat(row.get("retryCount")).isEqualTo("0"));
    observation("budget", Map.of("persistentAttempts", 4, "actualFixtureCalls", calls.get(),
        "physicalNotifications", 7, "allMessageRetryCountsZero", true, "attemptPersistedBeforeExternalCall", true,
        "status", status(), "deliveryClockAdvancedByFixture", true, "supplierRequestsClaim", false));
  }

  @Test
  @DisplayName("无正文接受和无执行凭据状态写回不得绕过执行保护")
  void executionUnsafeAcceptanceAndStateWritesRejected() throws Exception {
    assertThatThrownBy(() -> tasks.begin(kbId)).isInstanceOf(BusinessException.class)
        .hasMessageContaining("必须提供正文");
    var execution = acceptAndClaim("controlled unfenced write fixture");
    assertThatThrownBy(() -> tasks.markProcessing(kbId, generation)).isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> tasks.markFailed(kbId, generation, "unsafe failure")).isInstanceOf(BusinessException.class);
    assertThat(tasks.executionOwned(execution)).isTrue();
    assertThat(status()).isEqualTo("PROCESSING");
    observation("unfenced", Map.of("bodylessAcceptRejected", true, "unfencedStatusWritesRejected", true, "status", status()));
  }

  @Test
  @DisplayName("旧执行失败不能改变新执行或完成终态")
  void executionStaleFailureCannotChangeCurrentOwner() throws Exception {
    String content = "controlled stale failure fixture";
    var first = acceptAndClaim(content);
    expireLease();
    var second = tasks.claimExecution(kbId, generation, content).execution();
    assertThat(tasks.failExecution(first, "stale failed worker")).isEqualTo(KnowledgeBaseVectorTaskService.ExecutionFailure.SKIPPED);
    assertThat(tasks.executionOwned(second)).isTrue();
    assertThat(service.vectorizeAndStore(kbId, content, generation, new DocumentChunkingProperties(), second, () -> true)).isTrue();
    assertThat(tasks.failExecution(first, "late stale failure")).isEqualTo(KnowledgeBaseVectorTaskService.ExecutionFailure.SKIPPED);
    assertThat(status()).isEqualTo("COMPLETED");
    observation("stale-failure", Map.of("oldFence", first.fence(), "currentFence", second.fence(),
        "oldFailureBeforeAndAfterCompletionSkipped", true, "status", status()));
  }

  @Test
  @DisplayName("实际父锁等待跨越租约后不能用事务开始时间批准提交")
  void executionLockWaitCrossingLeaseMustRejectPromotion() throws Exception {
    String content = "controlled lock-wait expiry fixture";
    var execution = acceptAndClaim(content);
    FutureTask<Boolean> work = new FutureTask<>(() -> service.vectorizeAndStore(kbId, content, generation,
        new DocumentChunkingProperties(), execution, () -> true));
    Thread worker = new Thread(work, "controlled-vector-commit-wait");
    worker.setDaemon(true);
    boolean waited;
    try (Connection blocker = dataSource.getConnection()) {
      blocker.setAutoCommit(false);
      try (var statement = blocker.prepareStatement("SELECT id FROM knowledge_bases WHERE id=? FOR UPDATE")) {
        statement.setLong(1, kbId);
        try (var result = statement.executeQuery()) { assertThat(result.next()).isTrue(); }
      }
      worker.start();
      try {
        await(() -> jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() "
            + "AND pid<>pg_backend_pid() AND wait_event_type='Lock'", Integer.class) > 0);
        waited = true;
        Thread.sleep(1200);
        jdbc.update("UPDATE kb_vector_tasks SET execution_lease_until=clock_timestamp()-interval '100 milliseconds' WHERE generation=?", generation);
      } finally { blocker.commit(); }
    }
    try { assertThat(work.get(12, TimeUnit.SECONDS)).isFalse(); }
    finally { if (worker.isAlive()) { work.cancel(true); worker.join(5000); } }
    assertThat(vectors.countByKnowledgeBaseId(kbId)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id'=?",
        Integer.class, kbId.toString())).isZero();
    assertThat(status()).isEqualTo("PROCESSING");
    observation("lock-wait", Map.of("actualLockWaitObserved", waited, "fixtureLeaseAdvancedAfterTransactionStarted", true,
        "expiredPromotionRejected", true, "temporaryAndFormalVectorsZero", true, "recoveryLatencyClaim", false));
  }

  @Test
  @DisplayName("本地失权即使数据库租约未到期也不能继续计算或提交")
  void executionLocalRevocationMustNotPromote() throws Exception {
    String content = "controlled revoked local execution fixture";
    var execution = acceptAndClaim(content);
    AtomicInteger checks = new AtomicInteger();
    assertThat(service.vectorizeAndStore(kbId, content, generation, new DocumentChunkingProperties(), execution,
        () -> checks.incrementAndGet() == 1)).isFalse();
    assertThat(vectors.countByKnowledgeBaseId(kbId)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id'=?",
        Integer.class, kbId.toString())).isZero();
    assertThat(tasks.executionOwned(execution)).isTrue();
    observation("local-revocation", Map.of("externalBatchBeforeRevocation", true, "databaseLeaseStillValid", true,
        "revokedLocalPromotionRejected", true, "temporaryAndFormalVectorsZero", true));
  }

  @Test
  @DisplayName("执行字段迁移拒绝活跃任务，回滚不能丢弃未完成预算")
  void executionMigrationAndRollbackGuards() throws Exception {
    String schema = "rehevo_execution_schema_" + UUID.randomUUID().toString().replace("-", "");
    String tasksSql = Files.readString(Path.of("../docker/postgres/migrations/20261002_vector_tasks.sql"));
    String forward = Files.readString(Path.of("../docker/postgres/migrations/20261002_vector_execution.sql"));
    String rollback = Files.readString(Path.of("../docker/postgres/migrations/20261002_vector_execution_rollback.sql"));
    jdbc.execute("CREATE SCHEMA " + schema);
    try {
      jdbc.execute("CREATE TABLE " + schema + ".knowledge_bases(id bigint PRIMARY KEY,vector_status varchar(20),vector_generation varchar(36))");
      jdbc.execute("INSERT INTO " + schema + ".knowledge_bases VALUES(1,'COMPLETED',NULL)");
      migrate(schema, tasksSql);
      jdbc.execute("UPDATE " + schema + ".knowledge_bases SET vector_status='PROCESSING'");
      assertThatThrownBy(() -> migrate(schema, forward)).hasMessageContaining("finish or explicitly recover active tasks");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? "
          + "AND table_name='kb_vector_tasks' AND column_name='execution_attempts'", Integer.class, schema)).isZero();
      jdbc.execute("UPDATE " + schema + ".knowledge_bases SET vector_status='COMPLETED'");
      migrate(schema, forward);
      migrate(schema, forward);
      String taskId = UUID.randomUUID().toString();
      jdbc.update("INSERT INTO " + schema + ".kb_vector_tasks(generation,kb_id,file_sha256,content_sha256,parsed_content,"
          + "chunking_mode,max_tokens,state,next_delivery_at,created_at,updated_at) VALUES(?,1,?,?,?,'TOKEN',800,'ACTIVE',"
          + "clock_timestamp(),clock_timestamp(),clock_timestamp())", taskId, "a".repeat(64), "b".repeat(64), "controlled migration");
      assertThatThrownBy(() -> migrate(schema, rollback)).hasMessageContaining("Unfinished requests prevent removal");
      jdbc.update("UPDATE " + schema + ".kb_vector_tasks SET state='FAILED' WHERE generation=?", taskId);
      migrate(schema, rollback);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema=? "
          + "AND table_name='kb_vector_tasks' AND column_name='execution_attempts'", Integer.class, schema)).isZero();
      observation("migration", Map.of("unfinishedForwardRejected", true, "repeatedForwardPassed", true,
          "activeRollbackRejected", true, "terminalRollbackPassed", true, "shadowSchemaOnly", true));
    } finally { jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
  }

  private void migrate(String schema, String sql) {
    jdbc.execute((ConnectionCallback<Void>) connection -> {
      try (var statement = connection.createStatement()) {
        statement.execute("SET search_path TO " + schema);
        statement.execute(sql);
      }
      return null;
    });
  }

  private VectorTaskExecutionDTO acceptAndClaim(String content) {
    generation = tasks.begin(kbId, tasks.prepareInput(content));
    var claim = tasks.claimExecution(kbId, generation, content);
    assertThat(claim.status()).isEqualTo(VectorExecutionClaimDTO.Status.CLAIMED);
    return claim.execution();
  }
  private void expireLease() { jdbc.update("UPDATE kb_vector_tasks SET execution_lease_until=clock_timestamp()-interval '1 second' WHERE generation=?", generation); }
  private void enqueue(String content) { redis.streamAdd(stream(), Map.of("kbId", kbId.toString(), "content", content, "generation", generation, "retryCount", "0")); }
  private String status() { return jdbc.queryForObject("SELECT vector_status FROM knowledge_bases WHERE id=?", String.class, kbId); }
  private void observation(String name, Map<String, Object> value) throws Exception {
    Path directory = Path.of(System.getProperty("rehevo.fault.artifacts")).toAbsolutePath().normalize();
    Files.createDirectories(directory);
    Files.write(directory.resolve(name + ".json"), mapper.writeValueAsBytes(value));
    System.out.println("EXECUTION_CONTRACT " + name + " " + value);
  }
  private static String stream() { return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY; }
  private static String group() { return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME; }
}
