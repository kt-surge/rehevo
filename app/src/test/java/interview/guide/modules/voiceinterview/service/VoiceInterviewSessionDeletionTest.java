package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音会话删除后的缓存一致性")
class VoiceInterviewSessionDeletionTest {
  @Mock private VoiceInterviewSessionRepository sessionRepository;
  @Mock private VoiceInterviewMessageRepository messageRepository;
  @Mock private VoiceInterviewEvaluationRepository evaluationRepository;
  @Mock private RedissonClient redissonClient;
  @Mock private VoiceInterviewProperties properties;
  @Mock private VoiceEvaluateStreamProducer producer;
  @Mock private LlmProviderRegistry registry;
  @Mock private RBucket<VoiceInterviewSessionEntity> bucket;
  @InjectMocks private VoiceInterviewService service;

  @AfterEach
  void clearTransactionSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("事务提交前保留缓存，防止提交前读取数据库旧行")
  void doesNotEvictBeforeCommit() {
    when(sessionRepository.findByIdForUpdate(1L))
        .thenReturn(Optional.of(VoiceInterviewSessionEntity.builder().id(1L).build()));
    TransactionSynchronizationManager.initSynchronization();
    service.deleteSession(1L);
    verify(sessionRepository).deleteById(1L);
    verifyNoInteractions(redissonClient, producer, registry);
  }

  @Test
  @DisplayName("事务提交后清理精确会话缓存")
  void evictsAfterCommit() {
    when(sessionRepository.findByIdForUpdate(1L))
        .thenReturn(Optional.of(VoiceInterviewSessionEntity.builder().id(1L).build()));
    when(redissonClient.<VoiceInterviewSessionEntity>getBucket("voice:interview:session:1"))
        .thenReturn(bucket);
    TransactionSynchronizationManager.initSynchronization();
    service.deleteSession(1L);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verify(bucket).delete();
    verifyNoInteractions(producer, registry);
  }

  @Test
  @DisplayName("事务回滚不清理仍然有效的会话缓存")
  void rollbackDoesNotEvict() {
    when(sessionRepository.findByIdForUpdate(1L))
        .thenReturn(Optional.of(VoiceInterviewSessionEntity.builder().id(1L).build()));
    TransactionSynchronizationManager.initSynchronization();
    service.deleteSession(1L);
    TransactionSynchronizationManager.getSynchronizations().forEach(
        sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    verifyNoInteractions(redissonClient, producer, registry);
  }

  @Test
  @DisplayName("数据库删除失败时不登记失效或触发模型任务")
  void failedDeletionDoesNotEvict() {
    when(sessionRepository.findByIdForUpdate(1L))
        .thenReturn(Optional.of(VoiceInterviewSessionEntity.builder().id(1L).build()));
    doThrow(new IllegalStateException("controlled database failure"))
        .when(sessionRepository).deleteById(1L);
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> service.deleteSession(1L))
        .isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verifyNoInteractions(redissonClient, producer, registry);
  }

  @Test
  @DisplayName("无事务同步器调用仍立即清理已删除会话缓存")
  void directInvocationEvicts() {
    when(sessionRepository.findByIdForUpdate(1L))
        .thenReturn(Optional.of(VoiceInterviewSessionEntity.builder().id(1L).build()));
    when(redissonClient.<VoiceInterviewSessionEntity>getBucket("voice:interview:session:1"))
        .thenReturn(bucket);
    service.deleteSession(1L);
    verify(bucket).delete();
    verifyNoInteractions(producer, registry);
  }

  @Test
  @DisplayName("不存在的会话保持业务异常且不触碰无关缓存")
  void missingSessionKeepsBusinessError() {
    assertThatThrownBy(() -> service.deleteSession(1L)).isInstanceOf(BusinessException.class);
    verifyNoInteractions(messageRepository, evaluationRepository, redissonClient, producer, registry);
  }
}
