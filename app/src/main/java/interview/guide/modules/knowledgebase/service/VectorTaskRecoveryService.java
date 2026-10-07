package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;

/** 短事务领取、事务外投递、短事务回写；发送记录不是业务完成。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VectorTaskRecoveryService {
  private final KnowledgeBaseVectorTaskService tasks;
  private final VectorizeStreamProducer producer;

  @Scheduled(fixedDelayString = "${app.ai.rag.vector-task.scan-delay-ms:1000}")
  public void scheduledRecovery() {
    if (!tasks.durableEnabled()) { return; }
    try { recoverOnce(); }
    catch (Exception error) { log.error("向量任务恢复扫描失败，保留数据库恢复依据", error); }
  }

  public int recoverOnce() {
    var selected = tasks.claimNextDelivery();
    if (selected.isEmpty()) { return 0; }
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "任务投递不得位于数据库事务中");
    }
    var delivery = selected.get();
    String messageId = producer.sendRecovered(delivery);
    tasks.finishDelivery(delivery, messageId, messageId == null ? "实际通知未成功，等待下一次投递" : null);
    return messageId == null ? 0 : 1;
  }
}
