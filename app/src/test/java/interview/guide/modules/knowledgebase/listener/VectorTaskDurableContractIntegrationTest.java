package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.model.VectorTaskEntity;
import interview.guide.modules.knowledgebase.service.KnowledgeBasePersistenceService;
import interview.guide.modules.knowledgebase.service.VectorTaskRecoveryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

@DisplayName("真实持久化向量任务接受、投递与原子完成协议")
class VectorTaskDurableContractIntegrationTest extends VectorizeFaultIntegrationTest {
  @Autowired private KnowledgeBasePersistenceService persistence;
  @Autowired private DocumentChunkingProperties chunking;
  @Autowired private VectorTaskRecoveryService recovery;
  private String taskTrigger;

  @AfterEach
  void removeTaskFault() {
    if (taskTrigger != null) {
      jdbc.execute("DROP TRIGGER IF EXISTS " + taskTrigger + " ON kb_vector_tasks");
      jdbc.execute("DROP FUNCTION IF EXISTS " + taskTrigger + "()");
    }
    chunking.setMode(DocumentChunkingProperties.Mode.TOKEN);
    chunking.setMaxTokens(800);
  }

  @Test
  @DisplayName("任务正文持久化失败时当前版本与状态一起回滚")
  void durableAcceptanceFailureRollsBackRequestMarker() {
    taskInsertFault("NEW.kb_id=" + kbId);
    var before = knowledgeBases.findById(kbId).orElseThrow();
    assertThatThrownBy(() -> tasks.begin(kbId, tasks.prepareInput("controlled atomic accept failure")))
        .isInstanceOf(Exception.class);
    var after = knowledgeBases.findById(kbId).orElseThrow();
    assertThat(after.getVectorGeneration()).isEqualTo(before.getVectorGeneration());
    assertThat(after.getVectorStatus()).isEqualTo(before.getVectorStatus());
    assertThat(durableTasks.count()).isZero();
    System.out.println("DURABLE_ACCEPT_ROLLBACK versionPreserved=true statePreserved=true durableRows=0");
  }

  @Test
  @DisplayName("首上传文档与任务接受同事务，正文拒绝时新文档也回滚")
  void durableInitialDocumentAndTaskAreAtomic() {
    String content = "controlled first upload rollback fixture";
    taskInsertFault("NEW.parsed_content='" + content + "'");
    String fileHash = UUID.randomUUID().toString().replace("-", "")
        + UUID.randomUUID().toString().replace("-", "");
    MockMultipartFile file = new MockMultipartFile("file", "first-upload-fixture.txt", "text/plain",
        content.getBytes(StandardCharsets.UTF_8));
    var input = tasks.prepareInput(content);
    assertThatThrownBy(() -> persistence.saveKnowledgeBaseAndTask(file, "first-upload-fixture",
        "async-fault-20261001", "controlled-unwritten-storage-key", "controlled-storage-url", fileHash, input))
        .isInstanceOf(Exception.class);
    assertThat(knowledgeBases.findByFileHash(fileHash)).isEmpty();
    assertThat(durableTasks.count()).isZero();
    System.out.println("DURABLE_INITIAL_ROLLBACK documentPersisted=false taskPersisted=false externalStorageCalled=false");
  }

  @Test
  @DisplayName("新请求持久化快照并使前请求过期")
  void durableNewRequestObsoletesOldSnapshot() {
    String first = tasks.begin(kbId, tasks.prepareInput("controlled old request snapshot"));
    String second = tasks.begin(kbId, tasks.prepareInput("controlled current request snapshot"));
    assertThat(first).isNotEqualTo(second);
    assertThat(durableTasks.findById(first).orElseThrow().getState()).isEqualTo(VectorTaskEntity.State.OBSOLETE);
    assertThat(durableTasks.findById(second).orElseThrow().getState()).isEqualTo(VectorTaskEntity.State.ACTIVE);
    var claimed = tasks.claimNextDelivery().orElseThrow();
    assertThat(claimed.generation()).isEqualTo(second);
    assertThat(claimed.input().content()).isEqualTo("controlled current request snapshot");
    assertThat(knowledgeBases.findById(kbId).orElseThrow().getVectorGeneration()).isEqualTo(second);
    System.out.println("DURABLE_NEW_REQUEST oldState=OBSOLETE newestState=ACTIVE claimedNewest=true");
  }

  @Test
  @DisplayName("投递认领过期后迟到的 owner/fence 不得覆盖新的投递记录")
  void durableDeliveryFenceRejectsStaleWriter() {
    String accepted = tasks.begin(kbId, tasks.prepareInput("controlled delivery fence fixture"));
    var first = tasks.claimNextDelivery().orElseThrow();
    assertThat(tasks.claimNextDelivery()).isEmpty();
    jdbc.update("UPDATE kb_vector_tasks SET delivery_lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second' WHERE generation=?", accepted);
    var second = tasks.claimNextDelivery().orElseThrow();
    assertThat(second.fence()).isGreaterThan(first.fence());
    assertThat(tasks.finishDelivery(first, "old-message", null)).isFalse();
    assertThat(tasks.finishDelivery(second, "new-message", null)).isTrue();
    assertThat(durableTasks.findById(accepted).orElseThrow().getLastMessageId()).isEqualTo("new-message");
    System.out.println("DURABLE_DELIVERY_FENCE activeClaimExcluded=true staleWriteRejected=true leaseAdvancedByFixture=true");
  }

  @Test
  @DisplayName("真实 Redis 拒绝首次入队时保留任务正文和等待恢复状态")
  void durableDeniedNotificationKeepsRecoverySnapshot() throws Exception {
    var producer = new VectorizeStreamProducer(deniedXaddService(), new ApplicationMetrics(null), tasks);
    producer.sendVectorizeTask(kbId, "controlled denied durable notification fixture");
    var current = knowledgeBases.findById(kbId).orElseThrow();
    var task = durableTasks.findById(current.getVectorGeneration()).orElseThrow();
    assertThat(current.getVectorStatus()).isEqualTo(VectorStatus.PENDING);
    assertThat(task.getState()).isEqualTo(VectorTaskEntity.State.ACTIVE);
    assertThat(task.getParsedContent()).isEqualTo("controlled denied durable notification fixture");
    assertThat(task.getDeliveryAttempts()).isEqualTo(1);
    assertThat(task.getLastDeliveryError()).contains("NOPERM");
    assertThat(vectors.countByKnowledgeBaseId(kbId)).isZero();
    System.out.println("DURABLE_DENIED_NOTIFICATION status=PENDING taskState=ACTIVE contentPreserved=true actualRedisDenied=true");
  }

  @Test
  @DisplayName("恢复使用接受时的切块配置，并原子完成正文任务与正式索引")
  void durableChunkSnapshotSurvivesRuntimeConfigChange() throws Exception {
    chunking.setMaxTokens(64);
    String content = "controlled chunk snapshot fixture: PostgreSQL transaction protects current request content. ".repeat(80);
    var input = tasks.prepareInput(content);
    String accepted = tasks.begin(kbId, input);
    chunking.setMaxTokens(800);
    redis.streamAdd(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY, Map.of("kbId", kbId.toString(), "content", content,
        "generation", accepted, "retryCount", "0"));
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks);
    consumer.init();
    await(() -> knowledgeBases.findById(kbId).orElseThrow().getVectorStatus() == VectorStatus.COMPLETED
        && redis.streamGroupMetrics(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
            AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME).pending() == 0);
    stopConsumer();
    var completed = knowledgeBases.findById(kbId).orElseThrow();
    assertThat(completed.getChunkCount()).isGreaterThan(1);
    var task = durableTasks.findById(accepted).orElseThrow();
    assertThat(task.getState()).isEqualTo(VectorTaskEntity.State.COMPLETED);
    assertThat(task.getMaxTokens()).isEqualTo(64);
    assertThat(task.getParsedContent()).isEqualTo(content);
    System.out.println("DURABLE_CHUNK_SNAPSHOT acceptedTokens=64 currentTokens=800 completedAtomically=true chunks="
        + completed.getChunkCount());
  }

  private void taskInsertFault(String condition) {
    taskTrigger = "rehevo_delivery_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE FUNCTION " + taskTrigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "
        + condition + " THEN RAISE EXCEPTION 'controlled durable accept fault'; END IF; RETURN NEW; END $$");
    jdbc.execute("CREATE TRIGGER " + taskTrigger + " BEFORE INSERT ON kb_vector_tasks FOR EACH ROW EXECUTE FUNCTION "
        + taskTrigger + "()");
  }

  @Test
  @DisplayName("持久化任务完成写入拒绝时正式替换与父文档完成一起回滚")
  void durableCompletionWriteFailurePreservesOldFormalIndex() throws Exception {
    Document old = new Document("controlled original index before durable completion", Map.of("kb_id", kbId.toString()));
    writeFixtureVectors(List.of(old));
    String content = "controlled durable completion rollback fixture";
    String accepted = tasks.begin(kbId, tasks.prepareInput(content));
    taskTrigger = "rehevo_delivery_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE FUNCTION " + taskTrigger + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.kb_id="
        + kbId + " AND NEW.state='COMPLETED' THEN RAISE EXCEPTION 'controlled durable complete write fault'; "
        + "END IF; RETURN NEW; END $$");
    jdbc.execute("CREATE TRIGGER " + taskTrigger + " BEFORE UPDATE ON kb_vector_tasks FOR EACH ROW EXECUTE FUNCTION "
        + taskTrigger + "()");
    AtomicInteger attempts = new AtomicInteger();
    doAnswer(invocation -> { attempts.incrementAndGet(); writeFixtureVectors(invocation.getArgument(0)); return null; })
        .when(store).add(anyList());
    redis.streamAdd(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY, Map.of("kbId", kbId.toString(),
        "content", content, "generation", accepted, "retryCount", "0"));
    consumer = new VectorizeStreamConsumer(redis, new ApplicationMetrics(null), service, knowledgeBases, tasks);
    consumer.init();
    await(() -> {
      // Durable execution now defers retry in the database, not by resetting a new message counter.
      recovery.recoverOnce();
      return knowledgeBases.findById(kbId).orElseThrow().getVectorStatus() == VectorStatus.FAILED
          && redis.streamGroupMetrics(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
              AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME).pending() == 0;
    });
    stopConsumer();
    assertThat(attempts.get()).isEqualTo(4);
    assertThat(jdbc.queryForList("SELECT id::text FROM vector_store WHERE metadata->>'kb_id'=?",
        String.class, kbId.toString())).containsExactly(old.getId());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id'=?",
        Integer.class, kbId.toString())).isZero();
    assertThat(durableTasks.findById(accepted).orElseThrow().getState()).isEqualTo(VectorTaskEntity.State.FAILED);
    System.out.println("DURABLE_COMPLETE_ROLLBACK attempts=4 originalVectorIdPreserved=true parentState=FAILED taskState=FAILED temporaryVectors=0");
  }

  @Test
  @DisplayName("真实迁移拒绝缺快照未完成数据，回滚拒绝丢弃活跃任务")
  void durableMigrationGuardsAndRollback() throws Exception {
    String schema = "rehevo_delivery_schema_" + UUID.randomUUID().toString().replace("-", "");
    String forward = Files.readString(Path.of("../docker/postgres/migrations/20261002_vector_tasks.sql"));
    String rollback = Files.readString(Path.of("../docker/postgres/migrations/20261002_vector_tasks_rollback.sql"));
    jdbc.execute("CREATE SCHEMA " + schema);
    try {
      jdbc.execute("CREATE TABLE " + schema + ".knowledge_bases(id bigint PRIMARY KEY,vector_status varchar(20),vector_generation varchar(36))");
      jdbc.execute("INSERT INTO " + schema + ".knowledge_bases VALUES(1,'PROCESSING',NULL)");
      ConnectionCallback<Void> migrate = connection -> {
        try (var statement = connection.createStatement()) {
          statement.execute("SET search_path TO " + schema);
          statement.execute(forward);
        }
        return null;
      };
      ConnectionCallback<Void> revert = connection -> {
        try (var statement = connection.createStatement()) {
          statement.execute("SET search_path TO " + schema);
          statement.execute(rollback);
        }
        return null;
      };
      assertThatThrownBy(() -> jdbc.execute(migrate)).hasMessageContaining("Unfinished requests lack durable snapshots");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name='kb_vector_tasks'",
          Integer.class, schema)).isZero();
      jdbc.execute("UPDATE " + schema + ".knowledge_bases SET vector_status='COMPLETED'");
      jdbc.execute(migrate);
      jdbc.execute(migrate);
      String taskId = UUID.randomUUID().toString();
      jdbc.update("INSERT INTO " + schema + ".kb_vector_tasks(generation,kb_id,file_sha256,content_sha256,parsed_content,"
          + "chunking_mode,max_tokens,state,next_delivery_at,created_at,updated_at) VALUES(?,1,?,?,?,'TOKEN',800,'ACTIVE',"
          + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", taskId, "a".repeat(64), "b".repeat(64), "controlled migration fixture");
      assertThatThrownBy(() -> jdbc.execute(revert)).hasMessageContaining("Unfinished durable tasks remain");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name='kb_vector_tasks'",
          Integer.class, schema)).isEqualTo(1);
      jdbc.update("UPDATE " + schema + ".kb_vector_tasks SET state='FAILED' WHERE generation=?", taskId);
      jdbc.execute(revert);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=? AND table_name='kb_vector_tasks'",
          Integer.class, schema)).isZero();
      System.out.println("DURABLE_MIGRATION unfinishedMissingSnapshotRejected=true repeatedForward=true activeRollbackRejected=true terminalRollbackPassed=true");
    } finally { jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }
  }
}
