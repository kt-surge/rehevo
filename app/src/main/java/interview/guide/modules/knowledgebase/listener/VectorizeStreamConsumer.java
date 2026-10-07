package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import interview.guide.modules.knowledgebase.service.VectorTaskExecutionHeartbeat;
import interview.guide.modules.knowledgebase.model.VectorExecutionClaimDTO;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/** 消息的请求版本贯穿开始、重试、提升和状态写回。 */
@Slf4j
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload> {
  private final KnowledgeBaseVectorService vectorService;
  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final KnowledgeBaseVectorTaskService taskService;
  private final VectorTaskExecutionHeartbeat heartbeat;

  @Autowired
  public VectorizeStreamConsumer(RedisService redisService, ApplicationMetrics metrics,
      KnowledgeBaseVectorService vectorService, KnowledgeBaseRepository repository,
      KnowledgeBaseVectorTaskService taskService, VectorTaskExecutionHeartbeat heartbeat) {
    super(redisService, metrics);
    this.vectorService = vectorService;
    this.knowledgeBaseRepository = repository;
    this.taskService = taskService;
    this.heartbeat = heartbeat;
  }

  public VectorizeStreamConsumer(RedisService redisService, ApplicationMetrics metrics,
      KnowledgeBaseVectorService vectorService, KnowledgeBaseRepository repository,
      KnowledgeBaseVectorTaskService taskService) {
    this(redisService, metrics, vectorService, repository, taskService, null);
  }

  record VectorizePayload(Long kbId, String content, String generation) {}

  @Override protected String taskDisplayName() { return "向量化"; }
  @Override protected String streamKey() { return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY; }
  @Override protected String groupName() { return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME; }
  @Override protected String consumerPrefix() { return AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX; }
  @Override protected String threadName() { return "vectorize-consumer"; }

  @Override
  protected VectorizePayload parsePayload(StreamMessageId id, Map<String, String> data) {
    String kbId = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
    String content = data.get(AsyncTaskStreamConstants.FIELD_CONTENT);
    String generation = data.get(AsyncTaskStreamConstants.FIELD_GENERATION);
    if (kbId == null || content == null) { return null; }
    if (generation != null) { generation = UUID.fromString(generation).toString(); }
    return new VectorizePayload(Long.parseLong(kbId), content, generation);
  }

  @Override protected String payloadIdentifier(VectorizePayload payload) { return "kbId=" + payload.kbId(); }

  @Override
  protected boolean shouldSkip(VectorizePayload payload) {
    return knowledgeBaseRepository.findById(payload.kbId()).map(kb ->
        kb.getVectorStatus() == VectorStatus.COMPLETED
            || taskService.durableEnabled() && kb.getVectorStatus() == VectorStatus.FAILED
            || payload.generation() != null && !payload.generation().equals(kb.getVectorGeneration()))
        .orElse(true);
  }

  @Override
  protected boolean shouldRetainPending(VectorizePayload payload) {
    // 无版本且未完成的历史消息保持恢复入口，不猜测归属，不静默 ACK。
    return payload.generation() == null;
  }

  @Override
  protected boolean markProcessing(VectorizePayload payload) {
    return taskService.durableEnabled() || taskService.markProcessing(payload.kbId(), payload.generation());
  }

  @Override
  protected BusinessResult processBusiness(VectorizePayload payload) {
    if (taskService.durableEnabled()) { return processDurable(payload); }
    var accepted = taskService.acceptedInput(payload.kbId(), payload.generation());
    if (accepted.isPresent()) {
      var input = accepted.get();
      if (!input.content().equals(payload.content())) {
        throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "通知正文与接受快照不一致");
      }
      DocumentChunkingProperties snapshot = new DocumentChunkingProperties();
      snapshot.setMode(input.chunkingMode());
      snapshot.setMaxTokens(input.maxTokens());
      return vectorService.vectorizeAndStore(payload.kbId(), input.content(), payload.generation(), snapshot)
          ? BusinessResult.COMPLETED : BusinessResult.SKIPPED;
    }
    return vectorService.vectorizeAndStore(payload.kbId(), payload.content(), payload.generation())
        ? BusinessResult.COMPLETED : BusinessResult.SKIPPED;
  }

  private BusinessResult processDurable(VectorizePayload payload) {
    var claim = taskService.claimExecution(payload.kbId(), payload.generation(), payload.content());
    if (claim.status() == VectorExecutionClaimDTO.Status.SKIPPED) { return BusinessResult.SKIPPED; }
    if (claim.status() == VectorExecutionClaimDTO.Status.EXHAUSTED) { return BusinessResult.FAILED; }
    var execution = claim.execution();
    VectorTaskExecutionHeartbeat.Guard guard = null;
    try {
      if (heartbeat != null) { guard = heartbeat.monitor(execution); }
      var input = execution.input();
      DocumentChunkingProperties snapshot = new DocumentChunkingProperties();
      snapshot.setMode(input.chunkingMode());
      snapshot.setMaxTokens(input.maxTokens());
      VectorTaskExecutionHeartbeat.Guard monitor = guard;
      return vectorService.vectorizeAndStore(payload.kbId(), input.content(), payload.generation(), snapshot,
          execution, () -> (monitor == null || monitor.valid()) && taskService.executionOwned(execution))
          ? BusinessResult.COMPLETED : BusinessResult.SKIPPED;
    } catch (Exception error) {
      log.error("持久化向量执行失败: kbId={}, fence={}", payload.kbId(), execution.fence(), error);
      return switch (taskService.failExecution(execution, error.getMessage())) {
        case DEFERRED -> BusinessResult.DEFERRED;
        case FAILED -> BusinessResult.FAILED;
        case SKIPPED -> BusinessResult.SKIPPED;
      };
    } finally {
      if (guard != null) { guard.close(); }
    }
  }

  @Override
  protected void markCompleted(VectorizePayload payload) {
    // 正式向量和 COMPLETED 已在提升事务中一起提交，不做第二次独立状态写入。
  }

  @Override
  protected boolean markFailed(VectorizePayload payload, String error) {
    if (taskService.durableEnabled()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "执行状态写回失败，保留原 Pending 恢复入口");
    }
    return taskService.markFailed(payload.kbId(), payload.generation(), error);
  }

  @Override
  protected RetryResult retryMessage(VectorizePayload payload, int retryCount) {
    if (taskService.durableEnabled()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "持久化执行处理失败，保留原 Pending 恢复入口");
    }
    if (shouldSkip(payload)) { return RetryResult.SKIPPED; }
    try {
      redisService().streamAdd(streamKey(), Map.of(
          AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
          AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
          AsyncTaskStreamConstants.FIELD_GENERATION, payload.generation(),
          AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)),
          AsyncTaskStreamConstants.STREAM_MAX_LEN);
      return RetryResult.ENQUEUED;
    } catch (Exception error) {
      log.error("重试入队失败: kbId={}", payload.kbId(), error);
      return markFailed(payload, truncateError("重试入队失败: " + error.getMessage()))
          ? RetryResult.FAILED : RetryResult.SKIPPED;
    }
  }
}
