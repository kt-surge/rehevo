package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import interview.guide.modules.knowledgebase.model.VectorTaskInput;
import interview.guide.modules.knowledgebase.model.VectorTaskDeliveryDTO;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 分配新请求版本的事务返回之后才调用 Redis，重试消息沿用原版本。 */
@Component
public class VectorizeStreamProducer extends AbstractStreamProducer<VectorizeStreamProducer.VectorizeTaskPayload> {
  private final KnowledgeBaseVectorTaskService taskService;
  record VectorizeTaskPayload(Long kbId, String content, String generation) {}

  public VectorizeStreamProducer(RedisService redis, ApplicationMetrics metrics,
      KnowledgeBaseVectorTaskService taskService) {
    super(redis, metrics);
    this.taskService = taskService;
  }

  public void sendVectorizeTask(Long kbId, String content) {
    if (content == null || content.isBlank()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "向量内容不能为空");
    }
    VectorTaskInput input = taskService.prepareInput(content);
    String generation = taskService.begin(kbId, input);
    sendAccepted(kbId, input, generation);
  }

  public void sendAccepted(Long kbId, VectorTaskInput input, String generation) {
    String messageId = sendTask(new VectorizeTaskPayload(kbId, input.content(), generation));
    if (messageId != null) { taskService.initialDelivery(generation, messageId, null); }
  }

  /** 重发固定快照，不能分配新请求版本。 */
  public String sendRecovered(VectorTaskDeliveryDTO delivery) {
    return sendTask(new VectorizeTaskPayload(delivery.kbId(), delivery.input().content(), delivery.generation()));
  }

  @Override protected String taskDisplayName() { return "向量化"; }
  @Override protected String streamKey() { return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY; }
  @Override protected String payloadIdentifier(VectorizeTaskPayload payload) { return "kbId=" + payload.kbId(); }

  @Override
  protected Map<String, String> buildMessage(VectorizeTaskPayload payload) {
    return Map.of(AsyncTaskStreamConstants.FIELD_KB_ID, payload.kbId().toString(),
        AsyncTaskStreamConstants.FIELD_CONTENT, payload.content(),
        AsyncTaskStreamConstants.FIELD_GENERATION, payload.generation(),
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
  }

  @Override
  protected void onSendFailed(VectorizeTaskPayload payload, String error) {
    if (taskService.durableEnabled()) {
      taskService.initialDelivery(payload.generation(), null, truncateError(error));
    } else {
      taskService.markFailed(payload.kbId(), payload.generation(), truncateError(error));
    }
  }
}
