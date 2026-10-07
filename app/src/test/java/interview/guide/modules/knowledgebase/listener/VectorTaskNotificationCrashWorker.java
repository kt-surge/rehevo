package interview.guide.modules.knowledgebase.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorTaskRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Real XADD succeeds, then the owned child exits before the producer can record delivery. */
public final class VectorTaskNotificationCrashWorker {
  static final String CONTENT = "controlled post-XADD fixture: duplicate notifications keep one accepted version.";
  private VectorTaskNotificationCrashWorker() {}

  public static void main(String[] args) throws Exception {
    require("isolated-20261001".equals(System.getenv("REHEVO_ASYNC_FAULT")), "缺少隔离实验开关");
    require("true".equals(System.getenv("REHEVO_FAULT_DURABLE")), "必须使用候选任务接受");
    require(args.length == 2, "需要父实验文档及证据目录");
    Long kbId = Long.valueOf(args[0]);
    require(kbId > 4, "不能操作原四份公开资料");
    Path artifacts = Path.of(args[1]).toAbsolutePath().normalize();
    require(Files.isDirectory(artifacts) && artifacts.getFileName().toString().endsWith("-child"), "证据目录非法");
    try (var context = new AnnotationConfigApplicationContext(VectorizeFaultIntegrationTest.FaultConfig.class)) {
      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      require("rehevo_opt".equals(jdbc.queryForObject("SELECT current_database()", String.class)), "必须使用隔离库");
      KnowledgeBaseRepository repository = context.getBean(KnowledgeBaseRepository.class);
      VectorTaskRepository taskRepository = context.getBean(VectorTaskRepository.class);
      require("async-fault-20261001".equals(repository.findById(kbId).orElseThrow().getCategory()), "不是自己的文档");
      ObjectMapper mapper = context.getBean(ObjectMapper.class);
      RedisService afterXadd = new RedisService(context.getBean(RedissonClient.class)) {
        @Override
        public String streamAdd(String key, Map<String, String> message, int maxLen) {
          require(AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY.equals(key)
              && kbId.toString().equals(message.get("kbId")) && CONTENT.equals(message.get("content")), "只能发送自己的请求");
          require(!TransactionSynchronizationManager.isActualTransactionActive(), "Redis 调用不得位于数据库事务中");
          String messageId = super.streamAdd(key, message, maxLen);
          require(messageId != null, "实际 XADD 必须成功");
          var accepted = repository.findById(kbId).orElseThrow();
          var task = taskRepository.findById(message.get("generation")).orElseThrow();
          require(accepted.getVectorStatus() == VectorStatus.PENDING
              && message.get("generation").equals(accepted.getVectorGeneration()), "接受版本不一致");
          require(task.getLastMessageId() == null && task.getDeliveryAttempts() == 0, "投递记录不能提前写入");
          try {
            Files.write(artifacts.resolve("checkpoint.json"), mapper.writeValueAsBytes(Map.of(
                "kbId", kbId, "phase", "XADD_SUCCEEDED_BEFORE_RECORD", "haltCode", 75,
                "childPid", ProcessHandle.current().pid(), "generation", accepted.getVectorGeneration(),
                "messageId", messageId, "actualXaddCalled", true, "transactionActiveAtXadd", false)),
                StandardOpenOption.CREATE_NEW);
          } catch (Exception error) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "保存崩溃检查点失败", error);
          }
          Runtime.getRuntime().halt(75);
          throw new BusinessException(ErrorCode.INTERNAL_ERROR, "子进程没有退出");
        }
      };
      var producer = new VectorizeStreamProducer(afterXadd, new ApplicationMetrics(null),
          context.getBean(KnowledgeBaseVectorTaskService.class));
      producer.sendVectorizeTask(kbId, CONTENT);
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "未到达受控退出点");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) { throw new BusinessException(ErrorCode.INTERNAL_ERROR, message); }
  }
}
