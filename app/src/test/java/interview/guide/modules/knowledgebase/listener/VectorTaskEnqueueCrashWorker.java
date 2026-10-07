package interview.guide.modules.knowledgebase.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Actual producer accepts its request, then this disposable JVM halts before real XADD. */
public final class VectorTaskEnqueueCrashWorker {
  static final String CONTENT = "controlled enqueue recovery fixture: accepted request keeps its original content.";
  private VectorTaskEnqueueCrashWorker() {}

  public static void main(String[] args) throws Exception {
    require("isolated-20261001".equals(System.getenv("REHEVO_ASYNC_FAULT")), "缺少隔离实验开关");
    require(args.length == 2, "需要父实验文档及证据目录");
    Long kbId = Long.valueOf(args[0]);
    require(kbId > 4, "不能操作原四份公开资料");
    Path artifacts = Path.of(args[1]).toAbsolutePath().normalize();
    require(Files.isDirectory(artifacts) && artifacts.getFileName().toString().endsWith("-child"), "证据目录非法");
    try (var context = new AnnotationConfigApplicationContext(VectorizeFaultIntegrationTest.FaultConfig.class)) {
      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      require("rehevo_opt".equals(jdbc.queryForObject("SELECT current_database()", String.class)), "必须使用隔离库");
      KnowledgeBaseRepository repository = context.getBean(KnowledgeBaseRepository.class);
      require("async-fault-20261001".equals(repository.findById(kbId).orElseThrow().getCategory()), "不是自己的文档");
      ObjectMapper mapper = context.getBean(ObjectMapper.class);
      RedisService beforeXadd = new RedisService(context.getBean(RedissonClient.class)) {
        @Override
        public String streamAdd(String key, Map<String, String> message, int maxLen) {
          require(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY.equals(key)
              && kbId.toString().equals(message.get("kbId")) && CONTENT.equals(message.get("content")), "只能拦截自己的请求");
          var accepted = repository.findById(kbId).orElseThrow();
          require(accepted.getVectorStatus() == VectorStatus.PENDING
              && message.get("generation").equals(accepted.getVectorGeneration()), "必须在实际接受事务提交后退出");
          require(!TransactionSynchronizationManager.isActualTransactionActive(), "Redis 调用不得位于数据库事务中");
          try {
            Files.write(artifacts.resolve("checkpoint.json"), mapper.writeValueAsBytes(Map.of(
                "kbId", kbId, "phase", "ACCEPTED_BEFORE_XADD", "haltCode", 74,
                "childPid", ProcessHandle.current().pid(), "generation", accepted.getVectorGeneration(),
                "status", accepted.getVectorStatus().name(), "actualXaddCalled", false,
                "transactionActiveAtXadd", false)), StandardOpenOption.CREATE_NEW);
          } catch (Exception error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存崩溃检查点失败", error);
          }
          Runtime.getRuntime().halt(74);
          throw new BusinessException(ErrorCode.INTERNAL_ERROR, "子进程没有退出");
        }
      };
      var producer = new VectorizeStreamProducer(beforeXadd, new ApplicationMetrics(null),
          context.getBean(KnowledgeBaseVectorTaskService.class));
      producer.sendVectorizeTask(kbId, CONTENT);
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "未到达受控退出点");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, message); }
  }
}
