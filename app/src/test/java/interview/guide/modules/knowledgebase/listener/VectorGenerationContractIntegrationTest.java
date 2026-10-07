package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.springframework.jdbc.core.ConnectionCallback;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/** Only generation* methods are selected; inherited tests are run separately under their own class. */
@Tag("integration")
@DisplayName("真实向量请求版本、事务与迁移协议")
class VectorGenerationContractIntegrationTest extends VectorizeFaultIntegrationTest {
  @Test
  @DisplayName("真实生产者提交新版本后入队，旧消息先到也不得执行")
  void generationProducerAndReorderedMessages() throws Exception {
    VectorizeStreamProducer producer = new VectorizeStreamProducer(redis, new ApplicationMetrics(null), tasks);
    producer.sendVectorizeTask(kbId, "old queued generation fixture");
    producer.sendVectorizeTask(kbId, "new queued generation fixture");
    List<Map<String, String>> messages = ownMessages();
    assertThat(messages).hasSize(2);
    assertThat(messages.get(0).get("generation")).isNotEqualTo(messages.get(1).get("generation"));
    assertThat(currentGeneration()).isEqualTo(messages.get(1).get("generation"));
    AtomicInteger embeddings = new AtomicInteger();
    doAnswer(invocation -> {
      embeddings.incrementAndGet();
      writeFixtureVectors(invocation.getArgument(0));
      return null;
    }).when(store).add(anyList());
    startConsumer(redis);
    await(() -> status() == VectorStatus.COMPLETED && pending() == 0);
    stopConsumer();
    assertThat(embeddings.get()).isEqualTo(1);
    assertThat(contents()).hasSize(1).allMatch(content -> content.contains("new queued generation"));
    System.out.println("GENERATION_REORDER messages=2 embeddingFixtureCalls=1 newestGenerationPersisted=true pending=0");
  }

  @Test
  @DisplayName("重试消息沿用已提交版本，重试成功产生一个正式结果")
  void generationRetryPreservesVersion() throws Exception {
    AtomicInteger embeddings = new AtomicInteger();
    doAnswer(invocation -> {
      if (embeddings.incrementAndGet() == 1) {
        throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "controlled first attempt failure");
      }
      writeFixtureVectors(invocation.getArgument(0));
      return null;
    }).when(store).add(anyList());
    new VectorizeStreamProducer(redis, new ApplicationMetrics(null), tasks).sendVectorizeTask(kbId, "generation retry fixture");
    String accepted = currentGeneration();
    startConsumer(redis);
    await(() -> status() == VectorStatus.COMPLETED && pending() == 0);
    stopConsumer();
    assertThat(ownMessages()).hasSize(2).allMatch(message -> accepted.equals(message.get("generation")));
    assertThat(ownMessages().getLast().get("retryCount")).isEqualTo("1");
    assertThat(currentGeneration()).isEqualTo(accepted);
    assertThat(embeddings.get()).isEqualTo(2);
    assertThat(vectors.countByKnowledgeBaseId(kbId)).isEqualTo(1);
    System.out.println("GENERATION_RETRY messages=2 sameGeneration=true fixtureAttempts=2 formalVectors=1 pending=0");
  }

  @Test
  @DisplayName("完成状态被数据库拒绝时正式向量替换回滚，保留原索引")
  void generationCompletionWriteFailureRollsBackPromotion() throws Exception {
    String oldId = UUID.randomUUID().toString();
    jdbc.update("INSERT INTO vector_store(id,content,metadata,embedding) VALUES (?::uuid,?,?::json,?::vector)",
        oldId, "preserved original index fixture", "{\"kb_id\":\"" + kbId + "\"}",
        "[1," + "0,".repeat(1022) + "0]");
    triggerName = "rehevo_fault_" + UUID.randomUUID().toString().replace("-", "");
    jdbc.execute("CREATE FUNCTION " + triggerName + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
        + "IF NEW.id = " + kbId + " AND NEW.vector_status = 'COMPLETED' "
        + "THEN RAISE EXCEPTION 'controlled completion write failure'; END IF; RETURN NEW; END $$");
    jdbc.execute("CREATE TRIGGER " + triggerName + " BEFORE UPDATE ON knowledge_bases FOR EACH ROW EXECUTE FUNCTION " + triggerName + "()");
    new VectorizeStreamProducer(redis, new ApplicationMetrics(null), tasks).sendVectorizeTask(kbId, "candidate replacement fixture");
    startConsumer(redis);
    await(() -> status() == VectorStatus.FAILED && pending() == 0);
    stopConsumer();
    assertThat(jdbc.queryForList("SELECT id::text FROM vector_store WHERE metadata->>'kb_id' = ?", String.class, kbId.toString()))
        .containsExactly(oldId);
    assertThat(contents()).containsExactly("preserved original index fixture");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' = ?", Integer.class, kbId.toString())).isZero();
    assertThat(ownMessages()).hasSize(4);
    System.out.println("GENERATION_ATOMIC_COMPLETION status=FAILED originalVectorIdPreserved=true pendingVectors=0 attempts=4 pending=0");
  }

  @Test
  @DisplayName("普通 JPA 元数据保存不得回写旧请求版本、状态和块数")
  void generationStaleMetadataSaveCannotOverwriteTaskFields() {
    KnowledgeBaseEntity stale = knowledgeBases.findById(kbId).orElseThrow();
    String newest = tasks.begin(kbId);
    assertThat(tasks.markProcessing(kbId, newest)).isTrue();
    jdbc.update("UPDATE knowledge_bases SET chunk_count = 17 WHERE id = ?", kbId);
    stale.setName("metadata changed after newer task");
    stale.setVectorStatus(VectorStatus.FAILED);
    stale.setChunkCount(999);
    stale.setVectorError("stale metadata error");
    knowledgeBases.save(stale);
    assertThat(currentGeneration()).isEqualTo(newest);
    assertThat(status()).isEqualTo(VectorStatus.PROCESSING);
    assertThat(jdbc.queryForObject("SELECT chunk_count FROM knowledge_bases WHERE id = ?", Integer.class, kbId)).isEqualTo(17);
    assertThat(jdbc.queryForObject("SELECT vector_error FROM knowledge_bases WHERE id = ?", String.class, kbId)).isNull();
    assertThat(knowledgeBases.findById(kbId).orElseThrow().getName()).isEqualTo("metadata changed after newer task");
    System.out.println("GENERATION_METADATA staleSave=true currentGenerationPreserved=true status=PROCESSING chunkCount=17");
  }

  @Test
  @DisplayName("无版本未完成旧消息保留 Pending，新请求完成后再回收确认")
  void generationLegacyUnfinishedRetainsRecoveryEntrance() throws Exception {
    jdbc.update("UPDATE knowledge_bases SET vector_generation = NULL WHERE id = ?", kbId);
    String id = redis.streamAdd("knowledgebase:vectorize:stream", Map.of("kbId", kbId.toString(),
        "content", "legacy unversioned fixture", "retryCount", "0"));
    CountDownLatch processed = new CountDownLatch(1);
    RedisService observed = new RedisService(redisClient) {
      @Override
      public boolean streamConsumeMessages(String key, String group, String name, int count,
          long timeout, long idle, int claim, StreamMessageProcessor processor) {
        return super.streamConsumeMessages(key, group, name, count, timeout, idle, claim, (messageId, message) -> {
          processor.process(messageId, message);
          if (messageId.toString().equals(id)) { processed.countDown(); }
        });
      }
    };
    startConsumer(observed);
    assertThat(processed.await(12, TimeUnit.SECONDS)).isTrue();
    stopConsumer();
    assertThat(status()).isEqualTo(VectorStatus.PENDING);
    assertThat(currentGeneration()).isNull();
    assertThat(pending()).isEqualTo(1);
    assertThat(vectors.countByKnowledgeBaseId(kbId)).isZero();
    new VectorizeStreamProducer(redis, new ApplicationMetrics(null), tasks).sendVectorizeTask(kbId, "explicit new request resolves legacy document");
    startConsumer(redis);
    await(() -> status() == VectorStatus.COMPLETED);
    stopConsumer();
    assertThat(pending()).isEqualTo(1);
    String[] parts = id.split("-");
    advancePendingIdle(new StreamMessageId(Long.parseLong(parts[0]), Long.parseLong(parts[1])));
    startConsumer(redis);
    await(() -> pending() == 0);
    stopConsumer();
    assertThat(contents()).hasSize(1).allMatch(content -> content.contains("explicit new request"));
    System.out.println("GENERATION_LEGACY retainedPendingBeforeExplicitRequest=1 completedAfterNewRequest=true pendingAfterClaim=0 idleAdvancedByFixture=true");
  }

  @Test
  @DisplayName("显式迁移遇未完成旧任务则回滚，完成旧数据可重复迁移")
  void generationMigrationGuardAndIdempotency() throws Exception {
    String schema = "rehevo_generation_" + UUID.randomUUID().toString().replace("-", "");
    String sql = Files.readString(Path.of("../docker/postgres/migrations/20261001_vector_generation.sql"));
    jdbc.execute("CREATE SCHEMA " + schema);
    try {
      jdbc.execute("CREATE TABLE " + schema + ".knowledge_bases(id bigint PRIMARY KEY, vector_status varchar(20))");
      jdbc.execute("INSERT INTO " + schema + ".knowledge_bases VALUES(1,'PROCESSING')");
      ConnectionCallback<Void> migrate = connection -> {
        try (var statement = connection.createStatement()) {
          statement.execute("SET search_path TO " + schema);
          statement.execute(sql);
        }
        return null;
      };
      assertThatThrownBy(() -> jdbc.execute(migrate)).hasMessageContaining("Unfinished legacy vector jobs");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
          + "AND table_name = 'knowledge_bases' AND column_name = 'vector_generation'", Integer.class, schema)).isZero();
      jdbc.execute("UPDATE " + schema + ".knowledge_bases SET vector_status='COMPLETED'");
      jdbc.execute(migrate);
      jdbc.execute(migrate);
      assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema = ? "
          + "AND table_name='knowledge_bases' AND column_name='vector_generation'", Integer.class, schema)).isEqualTo(36);
      String rollback = Files.readString(Path.of("../docker/postgres/migrations/20261001_vector_generation_rollback.sql"));
      ConnectionCallback<Void> revert = connection -> {
        try (var statement = connection.createStatement()) {
          statement.execute("SET search_path TO " + schema);
          statement.execute(rollback);
        }
        return null;
      };
      jdbc.execute("UPDATE " + schema + ".knowledge_bases SET vector_status='PROCESSING'");
      assertThatThrownBy(() -> jdbc.execute(revert)).hasMessageContaining("Unfinished vector jobs prevent schema rollback");
      assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
          + "AND table_name='knowledge_bases' AND column_name='vector_generation'", Integer.class, schema)).isEqualTo(1);
      jdbc.execute("UPDATE " + schema + ".knowledge_bases SET vector_status='COMPLETED'");
      jdbc.execute(revert);
      assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? "
          + "AND table_name='knowledge_bases' AND column_name='vector_generation'", Integer.class, schema)).isZero();
      System.out.println("GENERATION_MIGRATION unfinishedGuardRolledBackColumn=true completedLegacyMigrated=true repeatedMigration=true "
          + "rollbackGuardPreservedColumn=true completedRollbackRemovedColumn=true");
    } finally {
      jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
    }
  }

  private void startConsumer(RedisService transport) {
    consumer = new VectorizeStreamConsumer(transport, new ApplicationMetrics(null), service, knowledgeBases, tasks);
    consumer.init();
  }

  private List<Map<String, String>> ownMessages() {
    return redisClient.<String, String>getStream("knowledgebase:vectorize:stream", StringCodec.INSTANCE)
        .range(StreamMessageId.MIN, StreamMessageId.MAX).values().stream()
        .filter(message -> kbId.toString().equals(message.get("kbId"))).toList();
  }

  private String currentGeneration() { return jdbc.queryForObject("SELECT vector_generation FROM knowledge_bases WHERE id = ?", String.class, kbId); }
  private VectorStatus status() { return knowledgeBases.findById(kbId).orElseThrow().getVectorStatus(); }
  private long pending() { return redis.streamGroupMetrics("knowledgebase:vectorize:stream", "vectorize-group").pending(); }
  private List<String> contents() { return jdbc.queryForList("SELECT content FROM vector_store WHERE metadata->>'kb_id' = ? ORDER BY id", String.class, kbId.toString()); }
}
