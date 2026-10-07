package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.config.VectorTaskRecoveryProperties;
import interview.guide.modules.knowledgebase.model.VectorTaskEntity;
import interview.guide.modules.knowledgebase.model.VectorTaskInput;
import interview.guide.modules.knowledgebase.model.VectorTaskDeliveryDTO;
import interview.guide.modules.knowledgebase.model.VectorTaskExecutionDTO;
import interview.guide.modules.knowledgebase.model.VectorExecutionClaimDTO;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.VectorTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.Optional;
import java.util.HexFormat;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** 向量请求版本与条件状态更新；调用方在短事务返回后执行 Redis/Embedding。 */
@Service
public class KnowledgeBaseVectorTaskService {
  private final KnowledgeBaseRepository repository;
  private final VectorTaskRepository durableTasks;
  private final VectorTaskRecoveryProperties recovery;
  private final DocumentChunkingProperties chunking;

  @Autowired
  public KnowledgeBaseVectorTaskService(KnowledgeBaseRepository repository, VectorTaskRepository durableTasks,
      VectorTaskRecoveryProperties recovery, DocumentChunkingProperties chunking) {
    this.repository = repository;
    this.durableTasks = durableTasks;
    this.recovery = recovery;
    this.chunking = chunking;
  }

  public KnowledgeBaseVectorTaskService(KnowledgeBaseRepository repository) {
    this(repository, null, new VectorTaskRecoveryProperties(), new DocumentChunkingProperties());
  }

  public boolean durableEnabled() { return recovery.isDurableEnabled(); }

  /** SHA 计算和配置快照在接受事务之前完成。 */
  public VectorTaskInput prepareInput(String content) {
    if (content == null || content.isBlank()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "向量内容不能为空");
    }
    try {
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(content.getBytes(StandardCharsets.UTF_8)));
      return new VectorTaskInput(content, hash, chunking.getMode(), chunking.getMaxTokens());
    } catch (NoSuchAlgorithmException error) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "正文指纹计算失败", error);
    }
  }

  @Transactional
  public String begin(Long kbId) {
    if (durableEnabled()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "持久化接受必须提供正文与配置快照");
    }
    String generation = UUID.randomUUID().toString();
    if (repository.beginVectorTask(kbId, generation) != 1) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
    }
    return generation;
  }

  @Transactional
  public String begin(Long kbId, VectorTaskInput input) {
    if (input == null || input.content() == null || input.content().isBlank()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "缺少接受请求正文");
    }
    String generation = UUID.randomUUID().toString();
    if (repository.beginVectorTask(kbId, generation) != 1) {
      throw new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在");
    }
    if (durableEnabled()) {
      Instant now = durableTasks.databaseNow();
      durableTasks.obsoletePrevious(kbId, generation, now);
      var parent = repository.findById(kbId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));
      VectorTaskEntity task = new VectorTaskEntity();
      task.setGeneration(generation);
      task.setKnowledgeBase(parent);
      task.setFileSha256(parent.getFileHash());
      task.setContentSha256(input.contentSha256());
      task.setParsedContent(input.content());
      task.setChunkingMode(input.chunkingMode());
      task.setMaxTokens(input.maxTokens());
      task.setState(VectorTaskEntity.State.ACTIVE);
      task.setMaxExecutionAttempts(recovery.getMaxExecutionAttempts());
      task.setNextDeliveryAt(now);
      task.setCreatedAt(now);
      task.setUpdatedAt(now);
      durableTasks.saveAndFlush(task);
    }
    return generation;
  }

  @Transactional(readOnly = true)
  public Optional<VectorTaskInput> acceptedInput(Long kbId, String generation) {
    if (!durableEnabled()) { return Optional.empty(); }
    var task = durableTasks.findById(generation)
        .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "请求缺少持久化快照，需按迁移清单恢复"));
    if (!task.getKnowledgeBase().getId().equals(kbId) || task.getState() != VectorTaskEntity.State.ACTIVE) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "请求快照不属于有效任务");
    }
    return Optional.of(inputOf(task));
  }

  @Transactional
  public void initialDelivery(String generation, String messageId, String error) {
    if (!durableEnabled()) { return; }
    Instant now = durableTasks.databaseNow();
    durableTasks.recordInitialDelivery(generation, messageId, bounded(error), now.plusMillis(messageId == null
        ? recovery.getRetryDelayMs() : recovery.getRedeliveryDelayMs()), now);
  }

  @Transactional
  public Optional<VectorTaskDeliveryDTO> claimNextDelivery() {
    if (!durableEnabled()) { return Optional.empty(); }
    var selected = durableTasks.lockNextDelivery();
    if (selected.isEmpty()) { return Optional.empty(); }
    VectorTaskEntity task = selected.getFirst();
    Instant now = durableTasks.databaseNow();
    task.setDeliveryOwner(UUID.randomUUID().toString());
    task.setDeliveryFence(task.getDeliveryFence() + 1);
    task.setDeliveryAttempts(task.getDeliveryAttempts() + 1);
    task.setDeliveryLeaseUntil(now.plusMillis(recovery.getDeliveryLeaseMs()));
    task.setUpdatedAt(now);
    durableTasks.saveAndFlush(task);
    return Optional.of(new VectorTaskDeliveryDTO(task.getKnowledgeBase().getId(), task.getGeneration(),
        inputOf(task), task.getDeliveryOwner(), task.getDeliveryFence()));
  }

  @Transactional
  public boolean finishDelivery(VectorTaskDeliveryDTO delivery, String messageId, String error) {
    Instant now = durableTasks.databaseNow();
    return durableTasks.finishDelivery(delivery.generation(), delivery.owner(), delivery.fence(), messageId,
        bounded(error), now.plusMillis(messageId == null ? recovery.getRetryDelayMs()
            : recovery.getRedeliveryDelayMs()), now) == 1;
  }

  private VectorTaskInput inputOf(VectorTaskEntity task) {
    return new VectorTaskInput(task.getParsedContent(), task.getContentSha256(), task.getChunkingMode(), task.getMaxTokens());
  }

  /** 父文档到任务固定锁顺序；获得执行权即消耗一次预算，先提交再调用模型。 */
  @Transactional
  public VectorExecutionClaimDTO claimExecution(Long kbId, String generation, String content) {
    if (!durableEnabled()) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "持久化执行未开启");
    }
    var parent = repository.lockById(kbId);
    if (parent.isEmpty() || !generation.equals(parent.get().getVectorGeneration())
        || parent.get().getVectorStatus() == VectorStatus.COMPLETED
        || parent.get().getVectorStatus() == VectorStatus.FAILED) {
      return new VectorExecutionClaimDTO(VectorExecutionClaimDTO.Status.SKIPPED, null);
    }
    VectorTaskEntity task = durableTasks.lockExecution(generation).orElseThrow(() ->
        new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "接受请求缺少任务快照，保留恢复入口"));
    if (!task.getKnowledgeBase().getId().equals(kbId) || task.getState() != VectorTaskEntity.State.ACTIVE) {
      return new VectorExecutionClaimDTO(VectorExecutionClaimDTO.Status.SKIPPED, null);
    }
    if (!task.getParsedContent().equals(content)) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "通知正文与接受快照不一致");
    }
    Instant now = durableTasks.databaseNow();
    if (task.getExecutionLeaseUntil() != null && task.getExecutionLeaseUntil().isAfter(now)) {
      return new VectorExecutionClaimDTO(VectorExecutionClaimDTO.Status.SKIPPED, null);
    }
    if (task.getExecutionAttempts() >= task.getMaxExecutionAttempts()) {
      failLocked(task, "执行预算耗尽，需显式发起新请求", now);
      return new VectorExecutionClaimDTO(VectorExecutionClaimDTO.Status.EXHAUSTED, null);
    }
    task.setExecutionOwner(UUID.randomUUID().toString());
    task.setExecutionFence(task.getExecutionFence() + 1);
    task.setExecutionAttempts(task.getExecutionAttempts() + 1);
    task.setExecutionLeaseUntil(now.plusMillis(recovery.getExecutionLeaseMs()));
    task.setUpdatedAt(now);
    VectorTaskExecutionDTO execution = new VectorTaskExecutionDTO(kbId, generation,
        task.getExecutionOwner(), task.getExecutionFence(), inputOf(task), task.getExecutionAttempts(), task.getMaxExecutionAttempts());
    durableTasks.saveAndFlush(task);
    if (repository.updateVectorTaskStatus(kbId, generation, VectorStatus.PROCESSING, null) != 1) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "执行认领与父状态未完整提交");
    }
    return new VectorExecutionClaimDTO(VectorExecutionClaimDTO.Status.CLAIMED, execution);
  }

  @Transactional(readOnly = true)
  public boolean executionOwned(VectorTaskExecutionDTO execution) {
    return durableTasks.executionOwned(execution.kbId(), execution.generation(), execution.owner(), execution.fence());
  }

  @Transactional
  public boolean renewExecution(VectorTaskExecutionDTO execution) {
    if (!lockCurrentParent(execution)) { return false; }
    var task = durableTasks.lockExecution(execution.generation());
    Instant now = durableTasks.databaseNow();
    if (task.isEmpty() || !owns(task.get(), execution, now)) { return false; }
    task.get().setExecutionLeaseUntil(now.plusMillis(recovery.getExecutionLeaseMs()));
    task.get().setUpdatedAt(now);
    durableTasks.saveAndFlush(task.get());
    return true;
  }

  public enum ExecutionFailure { DEFERRED, FAILED, SKIPPED }

  @Transactional
  public ExecutionFailure failExecution(VectorTaskExecutionDTO execution, String error) {
    if (!lockCurrentParent(execution)) { return ExecutionFailure.SKIPPED; }
    var selected = durableTasks.lockExecution(execution.generation());
    Instant now = durableTasks.databaseNow();
    if (selected.isEmpty() || !owns(selected.get(), execution, now)) { return ExecutionFailure.SKIPPED; }
    VectorTaskEntity task = selected.get();
    if (task.getExecutionAttempts() >= task.getMaxExecutionAttempts()) {
      failLocked(task, error, now);
      return ExecutionFailure.FAILED;
    }
    task.setExecutionOwner(null);
    task.setExecutionLeaseUntil(null);
    task.setLastExecutionError(bounded(error));
    task.setNextDeliveryAt(now.plusMillis(recovery.getRetryDelayMs()));
    task.setUpdatedAt(now);
    durableTasks.saveAndFlush(task);
    if (repository.updateVectorTaskStatus(execution.kbId(), execution.generation(), VectorStatus.PENDING, bounded(error)) != 1) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "执行重试状态未完整提交");
    }
    return ExecutionFailure.DEFERRED;
  }

  private boolean lockCurrentParent(VectorTaskExecutionDTO execution) {
    var parent = repository.lockById(execution.kbId());
    return parent.isPresent() && execution.generation().equals(parent.get().getVectorGeneration())
        && parent.get().getVectorStatus() == VectorStatus.PROCESSING;
  }

  public static boolean owns(VectorTaskEntity task, VectorTaskExecutionDTO execution, Instant now) {
    return task.getState() == VectorTaskEntity.State.ACTIVE
        && task.getKnowledgeBase().getId().equals(execution.kbId())
        && task.getGeneration().equals(execution.generation())
        && execution.owner().equals(task.getExecutionOwner()) && task.getExecutionFence() == execution.fence()
        && task.getExecutionLeaseUntil() != null && task.getExecutionLeaseUntil().isAfter(now);
  }

  private void failLocked(VectorTaskEntity task, String error, Instant now) {
    Long kbId = task.getKnowledgeBase().getId();
    String generation = task.getGeneration();
    task.setState(VectorTaskEntity.State.FAILED);
    task.setExecutionOwner(null);
    task.setExecutionLeaseUntil(null);
    task.setLastExecutionError(bounded(error));
    task.setCompletedAt(now);
    task.setUpdatedAt(now);
    durableTasks.saveAndFlush(task);
    if (repository.updateVectorTaskStatus(kbId, generation, VectorStatus.FAILED, bounded(error)) != 1) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "执行失败与父状态未完整提交");
    }
  }

  private String bounded(String error) {
    return error == null ? null : error.substring(0, Math.min(error.length(), 500));
  }

  @Transactional
  public boolean markProcessing(Long kbId, String generation) {
    if (durableEnabled()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "开始执行必须持有执行凭据");
    }
    return repository.updateVectorTaskStatus(kbId, generation, VectorStatus.PROCESSING, null) == 1;
  }

  @Transactional
  public boolean markFailed(Long kbId, String generation, String error) {
    if (durableEnabled()) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "失败写回必须持有执行凭据");
    }
    boolean changed = repository.updateVectorTaskStatus(kbId, generation, VectorStatus.FAILED, bounded(error)) == 1;
    if (changed && durableEnabled()) {
      durableTasks.finishTask(kbId, generation, VectorTaskEntity.State.FAILED, durableTasks.databaseNow());
    }
    return changed;
  }
}
