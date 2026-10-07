package interview.guide.modules.knowledgebase.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.DocumentChunkingService;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.repository.VectorTaskRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import interview.guide.modules.knowledgebase.service.VectorTaskExecutionHeartbeat;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** Actual disposable JVM exits inside VectorStore.add, after its temporary batch is persisted. */
public final class VectorExecutionCrashWorker {
  private VectorExecutionCrashWorker() {}

  public static void main(String[] args) throws Exception {
    require("isolated-20261001".equals(System.getenv("REHEVO_ASYNC_FAULT")), "缺少隔离开关");
    require(args.length == 3, "需要自己的文档、generation 与证据目录");
    Long kbId = Long.valueOf(args[0]);
    String generation = UUID.fromString(args[1]).toString();
    Path artifacts = Path.of(args[2]).toAbsolutePath().normalize();
    require(kbId > 4 && Files.isDirectory(artifacts)
        && artifacts.getFileName().toString().endsWith("-child"), "不是合法独立实验");
    try (var context = new AnnotationConfigApplicationContext(VectorizeFaultIntegrationTest.FaultConfig.class)) {
      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      ObjectMapper mapper = context.getBean(ObjectMapper.class);
      var knowledgeBases = context.getBean(KnowledgeBaseRepository.class);
      var durableTasks = context.getBean(VectorTaskRepository.class);
      var tasks = context.getBean(KnowledgeBaseVectorTaskService.class);
      var parent = knowledgeBases.findById(kbId).orElseThrow();
      require("rehevo_opt".equals(jdbc.queryForObject("SELECT current_database()", String.class))
          && "async-fault-20261001".equals(parent.getCategory())
          && generation.equals(parent.getVectorGeneration()) && tasks.durableEnabled(), "文档归属或模式不符");
      VectorStore store = mock(VectorStore.class);
      doAnswer(invocation -> {
        List<Document> batch = invocation.getArgument(0);
        require(batch.size() == 1, "仅允许独立单块样本");
        var task = durableTasks.findById(generation).orElseThrow();
        var processing = knowledgeBases.findById(kbId).orElseThrow();
        require(task.getExecutionAttempts() == 1 && task.getExecutionFence() == 1
            && task.getExecutionOwner() != null && processing.getVectorStatus() == VectorStatus.PROCESSING,
            "执行权和次数必须先持久化");
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "不能在数据库事务中调用外部计算");
        Document document = batch.getFirst();
        require(kbId.toString().equals(document.getMetadata().get("kb_target_id"))
            && generation.equals(document.getMetadata().get("kb_generation")), "只能写自己执行的临时块");
        jdbc.update("INSERT INTO vector_store(id,content,metadata,embedding) VALUES (?::uuid,?,?::json,?::vector)",
            document.getId(), document.getText(), mapper.writeValueAsString(document.getMetadata()),
            "[1," + "0,".repeat(1022) + "0]");
        Files.write(artifacts.resolve("checkpoint.json"), mapper.writeValueAsBytes(Map.of(
            "phase", "INSIDE_VECTORSTORE_AFTER_TEMPORARY_BATCH", "haltCode", 76,
            "childPid", ProcessHandle.current().pid(), "generation", generation,
            "persistedAttempts", 1, "persistedFence", 1, "temporaryVectorId", document.getId(),
            "fixtureCalls", 1, "transactionActiveDuringExternalCall", false,
            "shutdownHooksOrFinallyCalled", false)), StandardOpenOption.CREATE_NEW);
        Runtime.getRuntime().halt(76);
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "子进程没有退出");
      }).when(store).add(anyList());
      var service = new KnowledgeBaseVectorService(store, context.getBean(VectorRepository.class),
          context.getBean(TransactionalExecutor.class), new ApplicationMetrics(null),
          new DocumentChunkingService(new DocumentChunkingProperties()), durableTasks);
      var consumer = new VectorizeStreamConsumer(context.getBean(RedisService.class), new ApplicationMetrics(null),
          service, knowledgeBases, tasks, context.getBean(VectorTaskExecutionHeartbeat.class));
      consumer.init();
      new CountDownLatch(1).await(35, TimeUnit.SECONDS);
      consumer.shutdown();
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "未到达计算中退出点");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, message); }
  }
}
