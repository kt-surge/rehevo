package interview.guide.modules.knowledgebase.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.constant.AsyncTaskStreamConstants;
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
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Opt-in child JVM: complete real DB work, halt before the actual Redis XACK. */
public final class VectorizeCrashWorker {
  private VectorizeCrashWorker() {}

  public static void main(String[] args) throws Exception {
    require("isolated-20261001".equals(System.getenv("REHEVO_ASYNC_FAULT")), "缺少隔离实验开关");
    require(args.length == 3, "必须指定本实验创建的文档、消息和证据目录");
    Long kbId = Long.valueOf(args[0]);
    String expectedMessageId = args[1];
    require(kbId > 4, "不能对四篇公开资料触发崩溃");
    Path artifacts = Path.of(args[2]).toAbsolutePath().normalize();
    require(artifacts.getFileName().toString().endsWith("-child"), "子进程证据目录非法");
    require(Files.isDirectory(artifacts), "证据目录必须由父实验预先创建");

    try (var context = new AnnotationConfigApplicationContext(VectorizeFaultIntegrationTest.FaultConfig.class)) {
      KnowledgeBaseRepository knowledgeBases = context.getBean(KnowledgeBaseRepository.class);
      VectorRepository vectors = context.getBean(VectorRepository.class);
      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      ObjectMapper mapper = context.getBean(ObjectMapper.class);
      require("rehevo_opt".equals(jdbc.queryForObject("SELECT current_database()", String.class)),
          "不能在实验库之外执行崩溃");
      KnowledgeBaseEntity kb = knowledgeBases.findById(kbId).orElseThrow();
      require("async-fault-20261001".equals(kb.getCategory()), "不是本实验创建的文档");
      require(kb.getVectorStatus() == VectorStatus.PENDING, "子进程必须从未处理任务开始");
      AtomicInteger fixtureCalls = new AtomicInteger();
      VectorStore store = mock(VectorStore.class);
      doAnswer(invocation -> {
        fixtureCalls.incrementAndGet();
        List<Document> documents = invocation.getArgument(0);
        String vector = "[1," + "0,".repeat(1022) + "0]";
        for (Document document : documents) {
          jdbc.update("INSERT INTO vector_store(id,content,metadata,embedding) VALUES (?::uuid,?,?::json,?::vector)",
              document.getId(), document.getText(), mapper.writeValueAsString(document.getMetadata()), vector);
        }
        return null;
      }).when(store).add(anyList());
      KnowledgeBaseVectorService service = new KnowledgeBaseVectorService(store, vectors,
          context.getBean(TransactionalExecutor.class), new ApplicationMetrics(null),
          new DocumentChunkingService(new DocumentChunkingProperties()));
      RedisService crashRedis = new RedisService(context.getBean(RedissonClient.class)) {
        @Override
        public void streamAck(String key, String group, StreamMessageId... ids) {
          require(key.equals(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY)
              && group.equals(AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME)
              && ids.length == 1 && ids[0].toString().equals(expectedMessageId), "不能拦截其他消息的 ACK");
          KnowledgeBaseEntity completed = knowledgeBases.findById(kbId).orElseThrow();
          require(completed.getVectorStatus() == VectorStatus.COMPLETED, "崩溃点必须在完成状态提交之后");
          require(vectors.countByKnowledgeBaseId(kbId) == 1, "崩溃点必须已经提交一次业务结果");
          require(streamGroupMetrics(key, group).pending() == 1, "原消息应仍在 Redis Pending 中");
          try {
            Map<String, Object> checkpoint = Map.of("kbId", kbId, "messageId", expectedMessageId,
                "phase", "COMPLETED_BEFORE_XACK", "haltCode", 73,
                "childPid", ProcessHandle.current().pid(), "fixtureCalls", fixtureCalls.get(),
                "formalVectorIds", jdbc.queryForList("SELECT id::text FROM vector_store WHERE metadata->>'kb_id' = ? ORDER BY id",
                    String.class, kbId.toString()), "pending", 1, "actualXackCalled", false);
            Files.write(artifacts.resolve("checkpoint.json"), mapper.writeValueAsBytes(checkpoint),
                StandardOpenOption.CREATE_NEW);
          } catch (IOException error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "写入崩溃检查点失败", error);
          }
          // 仅此受控子 JVM；不执行 Spring/消费者关闭钩子，也不执行真实 XACK。
          Runtime.getRuntime().halt(73);
        }
      };
      VectorizeStreamConsumer consumer = new VectorizeStreamConsumer(crashRedis,
          new ApplicationMetrics(null), service, knowledgeBases, context.getBean(KnowledgeBaseVectorTaskService.class));
      consumer.init();
      new CountDownLatch(1).await(30, TimeUnit.SECONDS);
      consumer.shutdown();
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "子进程未到达受控崩溃点");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, message); }
  }
}
