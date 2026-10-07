package interview.guide.common.async;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.listener.EvaluateStreamConsumer;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.interview.service.AnswerEvaluationService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.resume.listener.AnalyzeStreamConsumer;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeGradingService;
import interview.guide.modules.resume.service.ResumePersistenceService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamConsumer;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("异步状态失败不得继续模型调用或确认原消息")
class ConsumerStatePersistenceTest {
  private final DataAccessResourceFailureException stateFailure =
      new DataAccessResourceFailureException("controlled state persistence failure");

  @Test
  @DisplayName("简历状态与重试终态均写入失败时保留原消息")
  void resumeStateFailureRetainsMessage() throws Exception {
    RedisService redis = mock(RedisService.class);
    ResumeRepository repository = mock(ResumeRepository.class);
    ResumeGradingService grading = mock(ResumeGradingService.class);
    ResumePersistenceService persistence = mock(ResumePersistenceService.class);
    ResumeEntity resume = new ResumeEntity();
    resume.setAnalyzeStatus(AsyncTaskStatus.PENDING);
    when(repository.findById(9001L)).thenReturn(Optional.of(resume));
    when(repository.save(any())).thenThrow(stateFailure);
    AnalyzeStreamConsumer consumer = new AnalyzeStreamConsumer(redis, new ApplicationMetrics(null),
        grading, persistence, repository);
    assertRecoverable(consumer, redis, Map.of("resumeId", "9001", "content", "controlled fixture"));
    verifyNoInteractions(grading, persistence);
  }

  @Test
  @DisplayName("文字评估状态与重试终态均写入失败时保留原消息")
  void textEvaluationStateFailureRetainsMessage() throws Exception {
    RedisService redis = mock(RedisService.class);
    InterviewSessionRepository repository = mock(InterviewSessionRepository.class);
    AnswerEvaluationService evaluation = mock(AnswerEvaluationService.class);
    InterviewPersistenceService persistence = mock(InterviewPersistenceService.class);
    LlmProviderRegistry providers = mock(LlmProviderRegistry.class);
    InterviewSessionEntity session = new InterviewSessionEntity();
    session.setEvaluateStatus(AsyncTaskStatus.PENDING);
    when(repository.findBySessionId("controlled-session")).thenReturn(Optional.of(session));
    when(repository.save(any())).thenThrow(stateFailure);
    EvaluateStreamConsumer consumer = new EvaluateStreamConsumer(redis, new ApplicationMetrics(null),
        repository, evaluation, persistence, new ObjectMapper(), providers);
    assertRecoverable(consumer, redis, Map.of("sessionId", "controlled-session"));
    verifyNoInteractions(evaluation, persistence, providers);
  }

  @Test
  @DisplayName("语音评估使用真实状态服务传播失败并保留原消息")
  void voiceEvaluationStateFailureRetainsMessage() throws Exception {
    RedisService redis = mock(RedisService.class);
    VoiceInterviewSessionRepository repository = mock(VoiceInterviewSessionRepository.class);
    VoiceInterviewEvaluationService evaluation = mock(VoiceInterviewEvaluationService.class);
    VoiceInterviewSessionEntity session = new VoiceInterviewSessionEntity();
    session.setEvaluateStatus(AsyncTaskStatus.PENDING);
    when(repository.findById(9001L)).thenReturn(Optional.of(session));
    when(repository.save(any())).thenThrow(stateFailure);
    VoiceInterviewService service = new VoiceInterviewService(repository,
        mock(VoiceInterviewMessageRepository.class), mock(VoiceInterviewEvaluationRepository.class),
        mock(RedissonClient.class), new VoiceInterviewProperties(),
        mock(VoiceEvaluateStreamProducer.class), mock(LlmProviderRegistry.class));
    VoiceEvaluateStreamConsumer consumer = new VoiceEvaluateStreamConsumer(redis, new ApplicationMetrics(null),
        service, evaluation, repository);
    assertRecoverable(consumer, redis, Map.of("voiceSessionId", "9001"));
    verifyNoInteractions(evaluation);
  }

  /** Actual consumer template/callback, fixture transport and repository: no real Redis claim here. */
  private void assertRecoverable(AbstractStreamConsumer<?> consumer, RedisService redis,
      Map<String, String> payload) throws Exception {
    CountDownLatch attempted = new CountDownLatch(1);
    AtomicBoolean firstRead = new AtomicBoolean(true);
    AtomicReference<Throwable> observed = new AtomicReference<>();
    when(redis.streamGroupMetrics(anyString(), anyString())).thenReturn(new RedisService.StreamGroupMetrics(0, 0, 0));
    when(redis.streamAdd(anyString(), anyMap(), anyInt()))
        .thenThrow(new DataAccessResourceFailureException("controlled retry transport failure"));
    doAnswer(invocation -> {
      if (firstRead.compareAndSet(true, false)) {
        RedisService.StreamMessageProcessor processor = invocation.getArgument(7);
        try {
          processor.process(new StreamMessageId(1, 0), payload);
          observed.set(new AssertionError("状态写入失败被吞掉"));
        } catch (Exception error) {
          observed.set(error);
        } finally {
          attempted.countDown();
        }
        return true;
      }
      new CountDownLatch(1).await(12, TimeUnit.SECONDS);
      return false;
    }).when(redis).streamConsumeMessages(anyString(), anyString(), anyString(), anyInt(),
        anyLong(), anyLong(), anyInt(), any());
    consumer.init();
    try {
      assertThat(attempted.await(12, TimeUnit.SECONDS)).isTrue();
      assertThat(observed.get()).isSameAs(stateFailure);
      verify(redis, never()).streamAck(anyString(), anyString(), any(StreamMessageId[].class));
    } finally {
      consumer.shutdown();
    }
  }
}
