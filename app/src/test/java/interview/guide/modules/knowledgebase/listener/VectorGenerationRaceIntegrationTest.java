package interview.guide.modules.knowledgebase.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.DocumentChunkingService;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.client.codec.StringCodec;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.lang.reflect.Constructor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Same A/B test source: marker column and message exist in A, but old code ignores them. */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "REHEVO_ASYNC_FAULT", matches = "isolated-20261001")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = VectorizeFaultIntegrationTest.FaultConfig.class)
@DisplayName("真实 PostgreSQL 与 Redis 旧请求迟到竞争对照")
class VectorGenerationRaceIntegrationTest {
  @Autowired private KnowledgeBaseRepository knowledgeBases;
  @Autowired private VectorRepository vectors;
  @Autowired private TransactionalExecutor transactions;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private RedisService redis;
  @Autowired private RedissonClient client;
  @Autowired private ObjectMapper mapper;
  @Autowired private ApplicationContext context;
  private final List<VectorizeStreamConsumer> workers = new ArrayList<>();
  private final CountDownLatch oldEntered = new CountDownLatch(1);
  private final CountDownLatch releaseOld = new CountDownLatch(1);
  private Long kbId;
  private String newestGeneration;

  @BeforeEach
  void prepare() {
    assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("rehevo_opt");
    assertThat(redis.streamGroupMetrics(stream(), group()).pending()).isZero();
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setFileHash(UUID.randomUUID().toString().replace("-", ""));
    kb.setName("generation-race-fixture");
    kb.setCategory("async-generation-20261001");
    kb.setOriginalFilename("controlled-generation.txt");
    kb.setVectorStatus(VectorStatus.PENDING);
    kbId = knowledgeBases.save(kb).getId();
    assertThat(kbId).isGreaterThan(4);
  }

  @AfterEach
  void cleanup() throws Exception {
    releaseOld.countDown();
    workers.forEach(VectorizeStreamConsumer::shutdown);
    await(() -> Thread.getAllStackTraces().keySet().stream()
        .noneMatch(thread -> thread.isAlive() && thread.getName().equals("vectorize-consumer")));
    if (kbId != null) {
      jdbc.update("DELETE FROM vector_store WHERE metadata->>'kb_id' = ? OR metadata->>'kb_target_id' = ?",
          kbId.toString(), kbId.toString());
      knowledgeBases.deleteById(kbId);
      client.<String, String>getStream(stream(), StringCodec.INSTANCE).range(StreamMessageId.MIN, StreamMessageId.MAX)
          .forEach((id, message) -> {
            if (kbId.toString().equals(message.get("kbId"))) { redis.streamAck(stream(), group(), id); }
          });
    }
  }

  @Test
  @DisplayName("旧请求后完成不得覆盖新请求正式向量")
  void oldSuccessAfterNewCompletionCannotReplaceNewestIndex() throws Exception {
    runRace(false);
  }

  @Test
  @DisplayName("旧请求后失败不得覆盖新请求完成状态")
  void oldFailureAfterNewCompletionCannotOverwriteNewestStatus() throws Exception {
    runRace(true);
  }

  private void runRace(boolean failOld) throws Exception {
    String oldGeneration = beginMarker();
    VectorStore store = mock(VectorStore.class);
    doAnswer(invocation -> {
      List<Document> documents = invocation.getArgument(0);
      boolean old = documents.getFirst().getText().contains("old-request-fixture");
      if (old) {
        oldEntered.countDown();
        assertThat(releaseOld.await(20, TimeUnit.SECONDS)).isTrue();
        if (failOld) {
          throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "controlled old request late failure");
        }
      }
      String vector = "[1," + "0,".repeat(1022) + "0]";
      for (Document document : documents) {
        jdbc.update("INSERT INTO vector_store(id,content,metadata,embedding) VALUES (?::uuid,?,?::json,?::vector)",
            document.getId(), document.getText(), mapper.writeValueAsString(document.getMetadata()), vector);
      }
      return null;
    }).when(store).add(anyList());
    KnowledgeBaseVectorService service = new KnowledgeBaseVectorService(store, vectors, transactions,
        new ApplicationMetrics(null), new DocumentChunkingService(new DocumentChunkingProperties()));
    enqueue(oldGeneration, "old-request-fixture: old accepted request, delayed embedding.", failOld ? 3 : 0);
    startWorker(service);
    assertThat(oldEntered.await(12, TimeUnit.SECONDS)).isTrue();
    newestGeneration = beginMarker();
    enqueue(newestGeneration, "new-request-fixture: latest accepted request, finishes first.", 0);
    startWorker(service);
    await(() -> status().equals("COMPLETED") && contents().stream().allMatch(text -> text.contains("new-request-fixture"))
        && contents().size() == 1);
    List<String> latestIds = ids();
    releaseOld.countDown();
    await(() -> redis.streamGroupMetrics(stream(), group()).pending() == 0);
    Map<String, Object> observation = Map.of("failOld", failOld, "status", status(), "contents", contents(),
        "sameNewestIds", latestIds.equals(ids()), "generationUnchanged", newestGeneration.equals(jdbc.queryForObject(
            "SELECT vector_generation FROM knowledge_bases WHERE id = ?", String.class, kbId)), "pending", 0);
    System.out.println("GENERATION_RACE " + observation);
    assertThat(status()).as("旧请求不得覆盖新请求完成状态").isEqualTo("COMPLETED");
    assertThat(contents()).allMatch(text -> text.contains("new-request-fixture"));
    assertThat(ids()).as("新请求提交的正式向量不能被旧请求替换").containsExactlyElementsOf(latestIds);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM vector_store WHERE metadata->>'kb_target_id' = ?",
        Integer.class, kbId.toString())).isZero();
  }

  private String beginMarker() {
    String generation = UUID.randomUUID().toString();
    jdbc.update("UPDATE knowledge_bases SET vector_generation = ?, vector_status = 'PENDING', vector_error = NULL WHERE id = ?",
        generation, kbId);
    return generation;
  }

  private void enqueue(String generation, String text, int retries) {
    redis.streamAdd(stream(), Map.of("kbId", kbId.toString(), "content", text,
        "generation", generation, "retryCount", String.valueOf(retries)));
  }

  private void startWorker(KnowledgeBaseVectorService service) throws Exception {
    // Constructor adaptation is transport scaffolding only; all business calls run actual A/B production code.
    Constructor<?> constructor = VectorizeStreamConsumer.class.getConstructors()[0];
    Object[] arguments = new Object[constructor.getParameterCount()];
    for (int index = 0; index < arguments.length; index++) {
      Class<?> type = constructor.getParameterTypes()[index];
      if (type == RedisService.class) { arguments[index] = redis; }
      else if (type == ApplicationMetrics.class) { arguments[index] = new ApplicationMetrics(null); }
      else if (type == KnowledgeBaseVectorService.class) { arguments[index] = service; }
      else if (type == KnowledgeBaseRepository.class) { arguments[index] = knowledgeBases; }
      else if (type == VectorRepository.class) { arguments[index] = vectors; }
      else { arguments[index] = context.getBean(type); }
    }
    VectorizeStreamConsumer consumer = (VectorizeStreamConsumer) constructor.newInstance(arguments);
    workers.add(consumer);
    consumer.init();
  }

  private String status() { return jdbc.queryForObject("SELECT vector_status FROM knowledge_bases WHERE id = ?", String.class, kbId); }
  private List<String> contents() { return jdbc.queryForList("SELECT content FROM vector_store WHERE metadata->>'kb_id' = ? ORDER BY id", String.class, kbId.toString()); }
  private List<String> ids() { return jdbc.queryForList("SELECT id::text FROM vector_store WHERE metadata->>'kb_id' = ? ORDER BY id", String.class, kbId.toString()); }
  private static String stream() { return "knowledgebase:vectorize:stream"; }
  private static String group() { return "vectorize-group"; }
  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(25); }
    assertThat(condition.getAsBoolean()).as("真实竞争必须在有界时间内到达预期观察点").isTrue();
  }
}
