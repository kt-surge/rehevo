package interview.guide.modules.knowledgebase.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.config.VectorTaskRecoveryProperties;
import interview.guide.common.config.VectorTaskExecutionConfiguration;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.common.metrics.AppMetricNames;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.DocumentChunkingService;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.repository.VectorTaskRepository;
import interview.guide.modules.knowledgebase.service.VectorTaskRecoveryService;
import interview.guide.modules.knowledgebase.service.VectorTaskExecutionHeartbeat;
import interview.guide.modules.knowledgebase.service.KnowledgeBasePersistenceService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScript;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/** Real isolated PostgreSQL/JPA/Redis; deterministic VectorStore fixture, no model call. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "REHEVO_ASYNC_FAULT", matches = "isolated-20261001")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = VectorizeFaultIntegrationTest.FaultConfig.class)
@DisplayName("隔离 PostgreSQL 与 Redis 向量故障实验")
class VectorizeFaultIntegrationTest {
  @Autowired protected KnowledgeBaseRepository knowledgeBases;
  @Autowired protected VectorRepository vectors;
  @Autowired protected TransactionalExecutor transactions;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected RecordingRedisService redis;
  @Autowired protected RedissonClient redisClient;
  @Autowired protected ObjectMapper mapper;
  @Autowired protected KnowledgeBaseVectorTaskService tasks;
  @Autowired protected VectorTaskRepository durableTasks;
  protected Long kbId;
  protected String generation;
  protected String triggerName;
  protected VectorStore store;
  protected KnowledgeBaseVectorService service;
  protected VectorizeStreamConsumer consumer;
  private RedissonClient deniedClient;
  private String deniedUser;

  @BeforeEach
  void prepare() {
    assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("rehevo_opt");
    assertThat(redis.streamGroupMetrics(stream(), group()).pending()).isZero();
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setFileHash(UUID.randomUUID().toString().replace("-", ""));
    kb.setName("async-fault-fixture");
    kb.setCategory("async-fault-20261001");
    kb.setOriginalFilename("controlled-fixture.txt");
    kb.setVectorStatus(VectorStatus.PENDING);
    kbId = knowledgeBases.save(kb).getId();
    if (tasks.durableEnabled()) {
      // This prepares a legacy fixture, not a production acceptance; each durable case accepts its own input.
      generation = UUID.randomUUID().toString();
      jdbc.update("UPDATE knowledge_bases SET vector_generation=? WHERE id=?", generation, kbId);
    } else { generation = tasks.begin(kbId); }
    store = mock(VectorStore.class);
    doAnswer(invocation -> {
      writeFixtureVectors(invocation.getArgument(0));
      return null;
    }).when(store).add(anyList());
    service = new KnowledgeBaseVectorService(store, vectors, transactions, new ApplicationMetrics(null),
        new DocumentChunkingService(new DocumentChunkingProperties()), durableTasks);
  }

  @AfterEach
  void cleanup() throws Exception {
    stopConsumer();
    if (triggerName != null) {
      jdbc.execute("DROP TRIGGER IF EXISTS " + triggerName + " ON knowledge_bases");
      jdbc.execute("DROP FUNCTION IF EXISTS " + triggerName + "()");
    }
    if (kbId != null) {
      // IDs are created by this test; the four public indexes are never modified.
      jdbc.update("DELETE FROM vector_store WHERE metadata->>'kb_id' = ? OR metadata->>'kb_target_id' = ?",
          kbId.toString(), kbId.toString());
      knowledgeBases.deleteById(kbId);
      for (StreamMessageId messageId : redis.created.getOrDefault(kbId, List.of())) {
        redis.streamAck(stream(), group(), messageId);
      }
    }
    if (deniedClient != null) {
      deniedClient.shutdown();
    }
    if (deniedUser != null) {
      redisCommand("ACL", "DELUSER", deniedUser);
    }
  }

  @Test
  @DisplayName("Embedding 期间删除文档不得产生孤儿正式向量")
  void deletionDuringEmbeddingDoesNotPromoteOrphanVectors() throws Exception {
    doAnswer(invocation -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive())
          .as("Embedding 不在数据库事务中").isFalse();
      knowledgeBases.deleteById(kbId);
      writeFixtureVectors(invocation.getArgument(0));
      return null;
    }).when(store).add(anyList());
    service.vectorizeAndStore(kbId, "controlled deletion fixture: Java transaction and vector test.", generation);
    System.out.println("FAULT_DELETE " + Map.of("kbId", kbId, "exists", knowledgeBases.existsById(kbId),
        "formalVectors", vectors.countByKnowledgeBaseId(kbId)));
    assertThat(knowledgeBases.existsById(kbId)).isFalse();
    assertThat(vectors.countByKnowledgeBaseId(kbId)).as("删除后不得提升为正式向量").isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' = ?",
        Integer.class, kbId.toString())).as("删除后临时片段也应清理").isZero();
  }

  @Test
  @DisplayName("所有状态写入失败时消息保留 Pending 且不得继续 Embedding")
  void stateWriteFailureKeepsRecoverablePending() throws Exception {
    triggerName = "rehevo_fault_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE FUNCTION " + triggerName + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
        + "BEGIN IF NEW.id = " + kbId + " THEN RAISE EXCEPTION 'controlled status-write fault'; END IF; "
        + "RETURN NEW; END $$");
    jdbc.execute("CREATE TRIGGER " + triggerName + " BEFORE UPDATE ON knowledge_bases "
        + "FOR EACH ROW EXECUTE FUNCTION " + triggerName + "()");
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks);
    int initialMessages = (int) redis.streamLen(stream());
    redis.streamAdd(stream(), message(Map.of("kbId", kbId.toString(),
        "content", "controlled status failure fixture", "retryCount", "0")));
    consumer.init();
    await(() -> vectors.countByKnowledgeBaseId(kbId) > 0 || redis.streamLen(stream()) >= initialMessages + 4);
    stopConsumer();
    System.out.println("FAULT_STATE " + Map.of("kbId", kbId,
        "status", knowledgeBases.findById(kbId).orElseThrow().getVectorStatus(),
        "formalVectors", vectors.countByKnowledgeBaseId(kbId),
        "pending", redis.streamGroupMetrics(stream(), group()).pending()));
    assertThat(knowledgeBases.findById(kbId).orElseThrow().getVectorStatus()).isEqualTo(VectorStatus.PENDING);
    assertThat(vectors.countByKnowledgeBaseId(kbId)).as("未持久化 PROCESSING 不能调用 Embedding").isZero();
    assertThat(redis.streamGroupMetrics(stream(), group()).pending()).as("状态失败不能 ACK 后静默丢任务").isPositive();
    assertThat(redis.created.get(kbId)).hasSize(AsyncTaskStreamConstants.MAX_RETRY_COUNT + 1);
    removeStatusFault();
    // Advance only this fixture's PEL idle value; this is not a wall-clock recovery performance claim.
    StreamMessageId pendingId = redis.created.get(kbId).getLast();
    redisClient.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
        "return redis.call('XCLAIM',KEYS[1],ARGV[1],ARGV[2],0,ARGV[3],'IDLE',ARGV[4])",
        RScript.ReturnType.LIST, List.of(stream()), group(), "controlled-idle-owner",
        pendingId.toString(), String.valueOf(AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS + 1));
    assertThat(redis.streamGroupMetrics(stream(), group()).oldestPendingIdleMillis())
        .isGreaterThanOrEqualTo(AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS);
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks);
    consumer.init();
    await(() -> knowledgeBases.findById(kbId).orElseThrow().getVectorStatus() == VectorStatus.COMPLETED
        && redis.streamGroupMetrics(stream(), group()).pending() == 0);
    stopConsumer();
    System.out.println("FAULT_RECOVER " + Map.of("kbId", kbId, "status", "COMPLETED",
        "formalVectors", vectors.countByKnowledgeBaseId(kbId), "pending", 0,
        "queuedAttempts", redis.created.get(kbId).size(), "idleAdvancedByFixture", true));
    assertThat(vectors.countByKnowledgeBaseId(kbId)).isEqualTo(1);
  }

  @Test
  @DisplayName("重复投递产生一次业务结果且两个消息都被确认")
  void duplicateDeliveryHasOneBusinessEffect() throws Exception {
    AtomicInteger embeddings = new AtomicInteger();
    doAnswer(invocation -> {
      embeddings.incrementAndGet();
      writeFixtureVectors(invocation.getArgument(0));
      return null;
    }).when(store).add(anyList());
    Map<String, String> payload = message(Map.of("kbId", kbId.toString(), "content", "controlled duplicate fixture", "retryCount", "0"));
    redis.streamAdd(stream(), payload);
    redis.streamAdd(stream(), payload);
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks);
    consumer.init();
    await(() -> knowledgeBases.findById(kbId).orElseThrow().getVectorStatus() == VectorStatus.COMPLETED
        && redis.streamGroupMetrics(stream(), group()).pending() == 0);
    stopConsumer();
    assertThat(embeddings.get()).isEqualTo(1);
    assertThat(vectors.countByKnowledgeBaseId(kbId)).isEqualTo(1);
    System.out.println("FAULT_DUPLICATE " + Map.of("messages", 2, "embeddingFixtureCalls", embeddings.get(),
        "formalVectors", 1, "pending", 0));
  }

  @Test
  @DisplayName("向量提升的父文档行锁阻止并发删除抢跑")
  void promotionTransactionSerializesDeletion() throws Exception {
    VectorRepository observed = spy(vectors);
    FutureTask<Void> deletion = new FutureTask<>(() -> {
      knowledgeBases.deleteById(kbId);
      service.deleteByKnowledgeBaseId(kbId);
      return null;
    });
    doAnswer(invocation -> {
      Boolean exists = (Boolean) invocation.callRealMethod();
      assertThat(exists).isTrue();
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
      ConnectionCallback<Boolean> autoCommit = connection -> connection.getAutoCommit();
      System.out.println("FAULT_LOCK_CONNECTION " + Map.of("autoCommit", jdbc.execute(autoCommit),
          "backendPid", jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class)));
      Thread.ofVirtual().name("controlled-kb-delete").start(deletion);
      await(() -> {
        // pg_stat_activity 在当前提升事务内缓存快照；每次观测先刷新，不能一直读首次快照。
        jdbc.execute("SELECT pg_stat_clear_snapshot()");
        return deletion.isDone() || jdbc.queryForObject(
            "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND wait_event_type = 'Lock' AND lower(query) LIKE '%knowledge_bases%'", Integer.class) > 0;
      });
      System.out.println("FAULT_LOCK_OBSERVATION " + Map.of("deletionDone", deletion.isDone(),
          "activities", jdbc.queryForList("SELECT pid,state,wait_event_type,wait_event,query FROM pg_stat_activity "
              + "WHERE datname = current_database() AND lower(query) LIKE '%knowledge_bases%'")));
      if (deletion.isDone()) { deletion.get(1, TimeUnit.SECONDS); }
      assertThat(deletion.isDone()).as("父文档删除必须等待提升事务释放行锁").isFalse();
      return exists;
    }).when(observed).lockExistingKnowledgeBase(kbId);
    KnowledgeBaseVectorService guarded = new KnowledgeBaseVectorService(store, observed, transactions,
        new ApplicationMetrics(null), new DocumentChunkingService(new DocumentChunkingProperties()));
    guarded.vectorizeAndStore(kbId, "controlled promotion lock fixture", generation);
    deletion.get(12, TimeUnit.SECONDS);
    assertThat(knowledgeBases.existsById(kbId)).isFalse();
    assertThat(vectors.countByKnowledgeBaseId(kbId)).isZero();
    System.out.println("FAULT_PARENT_LOCK deleteWaitObserved=true formalVectors=0");
  }

  @Test
  @DisplayName("真实 Redis 拒绝首次 XADD 后文档进入可见 FAILED 终态")
  void initialEnqueueDenialPersistsFailed() throws Exception {
    RedisService denied = deniedXaddService();
    VectorizeStreamProducer producer = new VectorizeStreamProducer(denied, new ApplicationMetrics(null), tasks);
    producer.sendVectorizeTask(kbId, "controlled first enqueue failure fixture");
    KnowledgeBaseEntity saved = knowledgeBases.findById(kbId).orElseThrow();
    assertThat(saved.getVectorStatus()).isEqualTo(VectorStatus.FAILED);
    assertThat(saved.getVectorError()).contains("NOPERM");
    assertThat(redis.streamGroupMetrics(stream(), group()).pending()).isZero();
    System.out.println("FAULT_ENQUEUE status=FAILED actualRedisXaddDenied=true");
  }

  @Test
  @DisplayName("真实 Redis 拒绝重试 XADD 后持久化 FAILED 才确认原消息")
  void retryEnqueueDenialPersistsFailedBeforeAck() throws Exception {
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    RedisService denied = deniedXaddService();
    doAnswer(invocation -> {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "controlled embedding failure");
    }).when(store).add(anyList());
    redis.streamAdd(stream(), message(Map.of("kbId", kbId.toString(), "content", "controlled retry failure fixture", "retryCount", "0")));
    consumer = new VectorizeStreamConsumer(denied, new ApplicationMetrics(meters), service, knowledgeBases, tasks);
    consumer.init();
    await(() -> knowledgeBases.findById(kbId).orElseThrow().getVectorStatus() == VectorStatus.FAILED
        && redis.streamGroupMetrics(stream(), group()).pending() == 0
        && taskCount(meters, "retry") + taskCount(meters, "failure") == 1);
    stopConsumer();
    assertThat(knowledgeBases.findById(kbId).orElseThrow().getVectorError()).contains("NOPERM");
    assertThat(vectors.countByKnowledgeBaseId(kbId)).isZero();
    assertThat(redis.created.get(kbId)).hasSize(1);
    System.out.println("FAULT_RETRY status=FAILED actualRedisXaddDenied=true pending=0 queuedMessages=1");
    System.out.println("FAULT_RETRY_METRICS " + Map.of("retry", taskCount(meters, "retry"),
        "failure", taskCount(meters, "failure")));
    assertThat(taskCount(meters, "retry")).as("XADD 被拒绝，不能计为成功进入重试队列").isZero();
    assertThat(taskCount(meters, "failure")).isEqualTo(1);
  }

  private static double taskCount(SimpleMeterRegistry meters, String status) {
    Counter counter = meters.find(AppMetricNames.ASYNC_STREAM_TASK)
        .tags("stream", "knowledgebase_vectorization", "status", status).counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  @DisplayName("重试 XADD 和 FAILED 写入都失败时保留 Pending，撤销故障后可恢复")
  void failedRetryAndTerminalWriteRetainsPendingUntilRecovery() throws Exception {
    triggerName = "rehevo_fault_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE FUNCTION " + triggerName + "() RETURNS trigger LANGUAGE plpgsql AS $$ "
        + "BEGIN IF NEW.id = " + kbId + " AND NEW.vector_status = 'FAILED' "
        + "THEN RAISE EXCEPTION 'controlled terminal-write fault'; END IF; RETURN NEW; END $$");
    jdbc.execute("CREATE TRIGGER " + triggerName + " BEFORE UPDATE ON knowledge_bases "
        + "FOR EACH ROW EXECUTE FUNCTION " + triggerName + "()");
    KnowledgeBaseVectorTaskService observed = mock(KnowledgeBaseVectorTaskService.class, delegatesTo(tasks));
    CountDownLatch terminalAttempted = new CountDownLatch(1);
    doAnswer(invocation -> {
      try {
        return tasks.markFailed(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
      } finally {
        terminalAttempted.countDown();
      }
    }).when(observed).markFailed(any(), any(), any());
    doAnswer(invocation -> {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "controlled embedding failure");
    }).when(store).add(anyList());
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    RedisService denied = deniedXaddService();
    redis.streamAdd(stream(), message(Map.of("kbId", kbId.toString(), "content", "controlled double failure fixture", "retryCount", "0")));
    consumer = new VectorizeStreamConsumer(denied, new ApplicationMetrics(meters), service, knowledgeBases, observed);
    consumer.init();
    assertThat(terminalAttempted.await(12, TimeUnit.SECONDS)).isTrue();
    stopConsumer();
    assertThat(knowledgeBases.findById(kbId).orElseThrow().getVectorStatus()).isEqualTo(VectorStatus.PROCESSING);
    assertThat(redis.streamGroupMetrics(stream(), group()).pending()).isEqualTo(1);
    assertThat(redis.created.get(kbId)).hasSize(1);
    assertThat(taskCount(meters, "retry")).isZero();
    assertThat(taskCount(meters, "failure")).isZero();
    System.out.println("FAULT_DOUBLE_FAILURE status=PROCESSING pending=1 retryMetric=0 failureMetric=0 terminalWriteRejected=true");
    removeStatusFault();
    doAnswer(invocation -> {
      writeFixtureVectors(invocation.getArgument(0));
      return null;
    }).when(store).add(anyList());
    advancePendingIdle(redis.created.get(kbId).getFirst());
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks);
    consumer.init();
    await(() -> knowledgeBases.findById(kbId).orElseThrow().getVectorStatus() == VectorStatus.COMPLETED
        && redis.streamGroupMetrics(stream(), group()).pending() == 0);
    stopConsumer();
    assertThat(vectors.countByKnowledgeBaseId(kbId)).isEqualTo(1);
    System.out.println("FAULT_DOUBLE_FAILURE_RECOVER status=COMPLETED pending=0 formalVectors=1 idleAdvancedByFixture=true");
  }

  @Test
  @DisplayName("真实子 JVM 在完成提交后 ACK 前退出，重启回收不得重复 Embedding")
  void completedBeforeAckCrashRecoversWithoutDuplicateEmbedding() throws Exception {
    String classpathFile = System.getProperty("rehevo.fault.classpath-file");
    String artifactProperty = System.getProperty("rehevo.fault.artifacts");
    assertThat(classpathFile).as("使用 fault-classpath.init.gradle 提供完整子 JVM classpath 文件").isNotBlank();
    String classpath = Files.readString(Path.of(classpathFile));
    assertThat(classpath).isNotBlank();
    assertThat(artifactProperty).isNotBlank();
    Path artifacts = Path.of(artifactProperty).toAbsolutePath().normalize();
    assertThat(artifacts.getFileName().toString()).endsWith("-child");
    Files.createDirectory(artifacts);
    String messageId = redis.streamAdd(stream(), message(Map.of("kbId", kbId.toString(),
        "content", "controlled real child JVM crash fixture", "retryCount", "0")));
    Path argumentFile = Files.createTempFile("rehevo-controlled-child-", ".args");
    Files.writeString(argumentFile, "-Dfile.encoding=UTF-8\n-cp\n\""
        + classpath.replace('\\', '/') + "\"\n"
        + VectorizeCrashWorker.class.getName() + "\n" + kbId + "\n" + messageId + "\n\""
        + artifacts.toString().replace('\\', '/') + "\"\n", StandardCharsets.UTF_8);
    Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
    Process child = new ProcessBuilder(java.toString(), "@" + argumentFile)
        .redirectErrorStream(true).redirectOutput(artifacts.resolve("child.log").toFile()).start();
    try {
      assertThat(child.waitFor(45, TimeUnit.SECONDS)).as("子 JVM 必须在有界时间内实际退出").isTrue();
      assertThat(child.exitValue()).as("必须是 halt(73)，不能把启动错误算为崩溃成功").isEqualTo(73);
    } finally {
      if (child.isAlive()) {
        child.destroyForcibly();
        assertThat(child.waitFor(5, TimeUnit.SECONDS)).isTrue();
      }
      Files.deleteIfExists(argumentFile);
    }
    var checkpoint = mapper.readTree(Files.readString(artifacts.resolve("checkpoint.json")));
    assertThat(checkpoint.path("phase").asText()).isEqualTo("COMPLETED_BEFORE_XACK");
    assertThat(checkpoint.path("childPid").asLong()).isEqualTo(child.pid());
    assertThat(checkpoint.path("actualXackCalled").asBoolean()).isFalse();
    assertThat(checkpoint.path("fixtureCalls").asInt()).isEqualTo(1);
    assertThat(knowledgeBases.findById(kbId).orElseThrow().getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
    assertThat(redis.streamGroupMetrics(stream(), group()).pending()).isEqualTo(1);
    List<String> originalIds = jdbc.queryForList(
        "SELECT id::text FROM vector_store WHERE metadata->>'kb_id' = ? ORDER BY id", String.class, kbId.toString());
    assertThat(originalIds).hasSize(1);
    AtomicInteger restartEmbeddings = new AtomicInteger();
    doAnswer(invocation -> {
      restartEmbeddings.incrementAndGet();
      writeFixtureVectors(invocation.getArgument(0));
      return null;
    }).when(store).add(anyList());
    advancePendingIdle(redis.created.get(kbId).getFirst());
    SimpleMeterRegistry meters = new SimpleMeterRegistry();
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(meters), service, knowledgeBases, tasks);
    consumer.init();
    await(() -> redis.streamGroupMetrics(stream(), group()).pending() == 0 && taskCount(meters, "skipped") == 1);
    stopConsumer();
    assertThat(restartEmbeddings.get()).isZero();
    assertThat(jdbc.queryForList("SELECT id::text FROM vector_store WHERE metadata->>'kb_id' = ? ORDER BY id",
        String.class, kbId.toString())).containsExactlyElementsOf(originalIds);
    Map<String, Object> recovery = Map.of("childExitCode", child.exitValue(), "actualChildExited", true,
        "statusAfterCrash", "COMPLETED", "pendingBeforeRestart", 1, "pendingAfterRestart", 0,
        "restartEmbeddingCalls", restartEmbeddings.get(), "sameVectorIds", true,
        "idleAdvancedByFixture", true, "skippedMetric", taskCount(meters, "skipped"));
    Files.write(artifacts.resolve("recovery.json"), mapper.writeValueAsBytes(recovery));
    System.out.println("FAULT_REAL_CRASH " + recovery);
  }

  protected void advancePendingIdle(StreamMessageId id) {
    redisClient.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
        "return redis.call('XCLAIM',KEYS[1],ARGV[1],ARGV[2],0,ARGV[3],'IDLE',ARGV[4])",
        RScript.ReturnType.LIST, List.of(stream()), group(), "controlled-idle-owner",
        id.toString(), String.valueOf(AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS + 1));
  }

  @Test
  @DisplayName("停止正在等待 Embedding 的消费者时保留原 Pending 消息")
  void interruptedShutdownDoesNotAcknowledgeUnfinishedBusiness() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    doAnswer(invocation -> {
      entered.countDown();
      new CountDownLatch(1).await(20, TimeUnit.SECONDS);
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "controlled blocked embedding");
    }).when(store).add(anyList());
    redis.streamAdd(stream(), message(Map.of("kbId", kbId.toString(), "content", "controlled shutdown fixture", "retryCount", "0")));
    redis.streamAdd(stream(), message(Map.of("kbId", kbId.toString(), "content", "controlled unread batch fixture", "retryCount", "0")));
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks);
    consumer.init();
    assertThat(entered.await(12, TimeUnit.SECONDS)).isTrue();
    stopConsumer();
    assertThat(redis.streamGroupMetrics(stream(), group()).pending()).isEqualTo(2);
    assertThat(redis.created.get(kbId)).hasSize(2);
    assertThat(knowledgeBases.findById(kbId).orElseThrow().getVectorStatus()).isEqualTo(VectorStatus.PROCESSING);
    assertThat(vectors.countByKnowledgeBaseId(kbId)).isZero();
    System.out.println("FAULT_STOP phase=EMBEDDING_AND_UNREAD_BATCH status=PROCESSING pending=2 queuedMessages=2 formalVectors=0");
  }

  private void removeStatusFault() {
    jdbc.execute("DROP TRIGGER " + triggerName + " ON knowledge_bases");
    jdbc.execute("DROP FUNCTION " + triggerName + "()");
    triggerName = null;
  }

  protected RedisService deniedXaddService() throws Exception {
    deniedUser = "rehevo-fault-" + UUID.randomUUID();
    redisCommand("ACL", "SETUSER", deniedUser, "on", "nopass", "~" + stream(), "+@all", "-xadd");
    Config config = new Config();
    config.useSingleServer().setAddress("redis://127.0.0.1:16387").setUsername(deniedUser)
        .setPassword(UUID.randomUUID().toString());
    deniedClient = Redisson.create(config);
    return new RedisService(deniedClient);
  }

  private static void redisCommand(String... arguments) throws IOException, InterruptedException {
    List<String> command = new ArrayList<>(List.of("docker", "exec", "rehevo-opt-20261001-redis-1", "redis-cli", "--raw"));
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    assertThat(process.waitFor()).isZero();
    assertThat(output).doesNotStartWith("ERR");
  }

  protected void stopConsumer() throws InterruptedException {
    if (consumer != null) {
      consumer.shutdown();
      await(() -> Thread.getAllStackTraces().keySet().stream()
          .noneMatch(thread -> thread.isAlive() && thread.getName().equals("vectorize-consumer")));
    }
  }

  protected void writeFixtureVectors(List<Document> documents) throws Exception {
    String vector = "[1," + "0,".repeat(1022) + "0]";
    for (Document document : documents) {
      jdbc.update("INSERT INTO vector_store(id,content,metadata,embedding) VALUES (?::uuid,?,?::json,?::vector)",
          document.getId(), document.getText(), mapper.writeValueAsString(document.getMetadata()), vector);
    }
  }

  private Map<String, String> message(Map<String, String> values) {
    Map<String, String> result = new HashMap<>(values);
    result.put("generation", generation);
    return result;
  }

  private static String stream() { return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY; }
  private static String group() { return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME; }

  protected static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    assertThat(condition.getAsBoolean()).as("故障任务须在有界时间内到达可观察状态").isTrue();
  }

  static class RecordingRedisService extends RedisService {
    private final Map<Long, List<StreamMessageId>> created = new ConcurrentHashMap<>();
    RecordingRedisService(RedissonClient client) { super(client); }
    @Override
    public String streamAdd(String key, Map<String, String> message, int maxLen) {
      String id = super.streamAdd(key, message, maxLen);
      String[] parts = id.split("-");
      Long target = Long.valueOf(message.get("kbId"));
      created.computeIfAbsent(target, ignored -> new CopyOnWriteArrayList<>())
          .add(new StreamMessageId(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
      return id;
    }
  }

  @Configuration
  @EnableTransactionManagement
  @EnableJpaRepositories(basePackageClasses = KnowledgeBaseRepository.class)
  @Import(VectorTaskExecutionConfiguration.class)
  static class FaultConfig {
    @Bean DataSource dataSource() {
      DriverManagerDataSource source = new DriverManagerDataSource();
      source.setDriverClassName("org.postgresql.Driver");
      source.setUrl("jdbc:postgresql://127.0.0.1:15439/rehevo_opt");
      source.setUsername("postgres");
      source.setPassword(System.getenv("REHEVO_FAULT_DB_PASSWORD"));
      return source;
    }
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(source);
      factory.setPackagesToScan("interview.guide.modules.knowledgebase.model");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none",
          "hibernate.physical_naming_strategy",
          "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
      return factory;
    }
    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }
    @Bean JdbcTemplate jdbcTemplate(DataSource source) { return new JdbcTemplate(source); }
    @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    @Bean VectorRepository vectorRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
      return new VectorRepository(jdbc, mapper);
    }
    @Bean TransactionalExecutor transactionalExecutor() { return new TransactionalExecutor(); }
    @Bean DocumentChunkingProperties documentChunkingProperties() { return new DocumentChunkingProperties(); }
    @Bean VectorTaskRecoveryProperties vectorTaskRecoveryProperties() {
      var properties = new VectorTaskRecoveryProperties();
      properties.setDurableEnabled("true".equals(System.getenv("REHEVO_FAULT_DURABLE")));
      if (properties.isDurableEnabled()) { properties.setRedeliveryDelayMs(1000); }
      return properties;
    }
    @Bean KnowledgeBaseVectorTaskService knowledgeBaseVectorTaskService(KnowledgeBaseRepository repository,
        VectorTaskRepository durableTasks, VectorTaskRecoveryProperties properties, DocumentChunkingProperties chunking) {
      return new KnowledgeBaseVectorTaskService(repository, durableTasks, properties, chunking);
    }
    @Bean VectorizeStreamProducer vectorizeStreamProducer(RecordingRedisService redis, KnowledgeBaseVectorTaskService tasks) {
      return new VectorizeStreamProducer(redis, new ApplicationMetrics(null), tasks);
    }
    @Bean VectorTaskRecoveryService vectorTaskRecoveryService(KnowledgeBaseVectorTaskService tasks, VectorizeStreamProducer producer) {
      return new VectorTaskRecoveryService(tasks, producer);
    }
    @Bean VectorTaskExecutionHeartbeat vectorTaskExecutionHeartbeat(KnowledgeBaseVectorTaskService tasks,
        VectorTaskRecoveryProperties properties, @Qualifier("vectorExecutionTaskScheduler") TaskScheduler scheduler) {
      return new VectorTaskExecutionHeartbeat(tasks, properties, scheduler);
    }
    @Bean KnowledgeBasePersistenceService knowledgeBasePersistenceService(KnowledgeBaseRepository repository,
        KnowledgeBaseVectorTaskService tasks) {
      return new KnowledgeBasePersistenceService(repository, tasks);
    }
    @Bean(destroyMethod = "shutdown") RedissonClient redissonClient() {
      Config config = new Config();
      config.useSingleServer().setAddress("redis://127.0.0.1:16387");
      return Redisson.create(config);
    }
    @Bean RecordingRedisService redisService(RedissonClient client) { return new RecordingRedisService(client); }
  }
}
