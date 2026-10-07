package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("文字会话单条与批量删除的缓存提交边界")
class InterviewSessionDeletionTest {
  @Mock private InterviewSessionRepository sessions;
  @Mock private InterviewAnswerRepository answers;
  @Mock private ResumeRepository resumes;
  @Mock private ObjectMapper mapper;
  @Mock private VoiceInterviewSessionRepository voiceSessions;
  @Mock private VoiceInterviewEvaluationRepository voiceEvaluations;
  @Mock private InterviewSessionCache cache;
  @InjectMocks private InterviewPersistenceService service;

  @AfterEach
  void clearSynchronization() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  @DisplayName("提交前不驱逐缓存")
  void keepsCacheBeforeCommit() {
    when(sessions.findBySessionId("owned-a")).thenReturn(Optional.of(session("owned-a")));
    TransactionSynchronizationManager.initSynchronization();
    service.deleteSessionBySessionId("owned-a");
    verifyNoInteractions(cache);
  }

  @Test
  @DisplayName("提交后清理精确会话缓存及其已有映射")
  void evictsAfterCommit() {
    when(sessions.findBySessionId("owned-a")).thenReturn(Optional.of(session("owned-a")));
    TransactionSynchronizationManager.initSynchronization();
    service.deleteSessionBySessionId("owned-a");
    TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    verify(cache).deleteSession("owned-a");
    verifyNoMoreInteractions(cache);
  }

  @Test
  @DisplayName("回滚保留缓存")
  void rollbackKeepsCache() {
    when(sessions.findBySessionId("owned-a")).thenReturn(Optional.of(session("owned-a")));
    TransactionSynchronizationManager.initSynchronization();
    service.deleteSessionBySessionId("owned-a");
    TransactionSynchronizationManager.getSynchronizations().forEach(
        sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    verifyNoInteractions(cache);
  }

  @Test
  @DisplayName("数据库删除失败不登记失效")
  void failedDeleteDoesNotEvict() {
    var entity = session("owned-a");
    when(sessions.findBySessionId("owned-a")).thenReturn(Optional.of(entity));
    doThrow(new IllegalStateException("controlled DB failure")).when(sessions).delete(entity);
    TransactionSynchronizationManager.initSynchronization();
    assertThatThrownBy(() -> service.deleteSessionBySessionId("owned-a")).isInstanceOf(IllegalStateException.class);
    TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    verifyNoInteractions(cache);
  }

  @Test
  @DisplayName("简历批量删除只失效查询得到的两个会话")
  void bulkEvictsExactSessionsAfterCommit() {
    var owned = List.of(session("owned-a"), session("owned-b"));
    when(sessions.findByResumeIdOrderByCreatedAtDesc(42L)).thenReturn(owned);
    TransactionSynchronizationManager.initSynchronization();
    service.deleteSessionsByResumeId(42L);
    verifyNoInteractions(cache);
    TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
    verify(cache).deleteSession("owned-a");
    verify(cache).deleteSession("owned-b");
    verifyNoMoreInteractions(cache);
  }

  @Test
  @DisplayName("无同步器直接调用在删除成功后立即清理")
  void directInvocationEvicts() {
    when(sessions.findBySessionId("owned-a")).thenReturn(Optional.of(session("owned-a")));
    service.deleteSessionBySessionId("owned-a");
    verify(cache).deleteSession("owned-a");
  }

  @Test
  @DisplayName("缺失会话保留业务错误且不触碰缓存")
  void missingSessionDoesNotEvict() {
    assertThatThrownBy(() -> service.deleteSessionBySessionId("owned-missing"))
        .isInstanceOf(BusinessException.class);
    verifyNoInteractions(cache);
  }

  private InterviewSessionEntity session(String id) {
    var session = new InterviewSessionEntity();
    session.setSessionId(id);
    return session;
  }
}
