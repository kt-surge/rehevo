package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.controller.VoiceInterviewController;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Tag("integration")
@SpringJUnitConfig(VoiceEvaluationCommitIntegrationTest.Config.class)
@DisplayName("语音报告与完成状态真实事务边界，外部模型替身")
class VoiceEvaluationCommitIntegrationTest {
  @Autowired private VoiceInterviewSessionRepository sessions;
  @Autowired private VoiceInterviewMessageRepository messages;
  @Autowired private VoiceInterviewEvaluationRepository reports;
  @Autowired private VoiceInterviewEvaluationService evaluation;
  @Autowired private VoiceInterviewService voice;
  @Autowired private UnifiedEvaluationService model;
  @Autowired private InterviewSkillService skills;
  @Autowired private LlmProviderRegistry providers;
  @Autowired private RedissonClient redis;
  @Autowired private DataSource dataSource;
  private RBucket<VoiceInterviewSessionEntity> bucket;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void resetOwnedFixtures() {
    reports.deleteAll();
    messages.deleteAll();
    sessions.deleteAll();
    reset(model, skills, providers, redis);
    bucket = mock(RBucket.class);
    when(redis.<VoiceInterviewSessionEntity>getBucket(anyString())).thenReturn(bucket);
    when(skills.buildEvaluationReferenceSectionSafe(anyString())).thenReturn("公开受控技术参考");
  }

  @Test
  @DisplayName("空对话报告与 COMPLETED 一次提交，未调用模型")
  void emptyResultCompletesAtomically() {
    var session = session(false);
    evaluation.generateEvaluation(session.getId());
    assertThat(reports.findBySessionId(session.getId())).isPresent();
    assertThat(sessions.findById(session.getId()).orElseThrow().getEvaluateStatus())
        .isEqualTo(AsyncTaskStatus.COMPLETED);
    verifyNoInteractions(model, providers);
  }

  @Test
  @DisplayName("重复投递保持同一报告，不撞唯一约束且已完成重放不再评估")
  void duplicateDeliveryReusesReport() {
    var session = session(true);
    when(model.evaluate(any(), anyString(), any(), isNull(), anyString())).thenReturn(report(session.getId()));
    evaluation.generateEvaluation(session.getId());
    Long id = reports.findBySessionId(session.getId()).orElseThrow().getId();
    assertThatCode(() -> evaluation.generateEvaluation(session.getId())).doesNotThrowAnyException();
    assertThat(reports.findAll()).singleElement().extracting(entity -> entity.getId()).isEqualTo(id);
    verify(model, times(1))
        .evaluate(any(), anyString(), any(), isNull(), anyString());
  }

  @Test
  @DisplayName("模型生成期间删除会话，提交结果不创建孤立报告")
  void deletionDuringGenerationLeavesNoOrphan() {
    var session = session(true);
    when(model.evaluate(any(), anyString(), any(), isNull(), anyString())).thenAnswer(invocation -> {
      voice.deleteSession(session.getId());
      return report(session.getId());
    });
    evaluation.generateEvaluation(session.getId());
    assertThat(sessions.findById(session.getId())).isEmpty();
    assertThat(reports.findBySessionId(session.getId())).isEmpty();
  }

  @Test
  @DisplayName("完成状态在真实数据库约束处失败，报告插入也回滚")
  void failedCompletionRollsBackInsertedReport() {
    var session = session(true);
    when(model.evaluate(any(), anyString(), any(), isNull(), anyString())).thenReturn(report(session.getId()));
    var jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("ALTER TABLE voice_interview_sessions ADD CONSTRAINT controlled_completion_failure "
        + "CHECK (evaluate_status <> 'COMPLETED')");
    try {
      assertThatThrownBy(() -> evaluation.generateEvaluation(session.getId())).isInstanceOf(Exception.class);
      assertThat(reports.findBySessionId(session.getId())).isEmpty();
      assertThat(sessions.findById(session.getId()).orElseThrow().getEvaluateStatus())
          .isEqualTo(AsyncTaskStatus.PROCESSING);
      verifyNoInteractions(bucket);
    } finally {
      jdbc.execute("ALTER TABLE voice_interview_sessions DROP CONSTRAINT controlled_completion_failure");
    }
  }

  @Test
  @DisplayName("评估调用不持有事务，报告使用提交时重新读取的会话元数据")
  void modelOutsideTransactionAndFreshMetadata() {
    var session = session(true);
    var outside = new AtomicBoolean();
    when(model.evaluate(any(), anyString(), any(), isNull(), anyString())).thenAnswer(invocation -> {
      outside.set(!TransactionSynchronizationManager.isActualTransactionActive());
      var latest = sessions.findById(session.getId()).orElseThrow();
      latest.setRoleType("controlled-updated-role");
      sessions.saveAndFlush(latest);
      return report(session.getId());
    });
    evaluation.generateEvaluation(session.getId());
    assertThat(outside).isTrue();
    assertThat(reports.findBySessionId(session.getId()).orElseThrow().getInterviewerRole())
        .isEqualTo("controlled-updated-role");
  }

  @Test
  @DisplayName("评估轮询读取数据库完成状态，不被旧会话缓存遮蔽")
  void pollingUsesCommittedState() {
    var session = session(false);
    when(bucket.get()).thenReturn(session);
    evaluation.generateEvaluation(session.getId());
    var producer = mock(VoiceEvaluateStreamProducer.class);
    var controller = new VoiceInterviewController(voice, evaluation, producer);
    var response = controller.getEvaluation(session.getId());
    assertThat(response.getData().getEvaluateStatus()).isEqualTo("COMPLETED");
    assertThat(response.getData().getEvaluation()).isNotNull();
  }

  private VoiceInterviewSessionEntity session(boolean withMessage) {
    var session = sessions.saveAndFlush(VoiceInterviewSessionEntity.builder().roleType("java-backend")
        .evaluateStatus(AsyncTaskStatus.PROCESSING).evaluateError("controlled previous failure").build());
    if (withMessage) {
      messages.saveAndFlush(VoiceInterviewMessageEntity.builder().sessionId(session.getId())
          .sequenceNum(0).messageType("USER_SPEECH").aiGeneratedText("Redis ACK 的作用是什么？")
          .userRecognizedText("确认消费组中的消息已处理。").build());
    }
    return session;
  }

  private EvaluationReport report(Long id) {
    return new EvaluationReport(id.toString(), 1, 1, 0, 1, 0, 0.0, 0.0, 0,
        List.of(), List.of(), "受控报告，未执行真实模型评分", List.of(), List.of(), List.of(), List.of());
  }

  @Configuration
  @EnableTransactionManagement
  @EnableJpaRepositories(basePackageClasses = VoiceInterviewSessionRepository.class)
  static class Config {
    @Bean DataSource dataSource() {
      return new DriverManagerDataSource(
          "jdbc:h2:mem:voice-commit;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan("interview.guide.modules.voiceinterview.model");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.show_sql", "false"));
      return factory;
    }
    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }
    @Bean UnifiedEvaluationService model() { return mock(UnifiedEvaluationService.class); }
    @Bean LlmProviderRegistry providers() { return mock(LlmProviderRegistry.class); }
    @Bean InterviewSkillService skills() { return mock(InterviewSkillService.class); }
    @Bean RedissonClient redis() { return mock(RedissonClient.class); }
    @Bean VoiceInterviewService voice(VoiceInterviewSessionRepository sessions,
        VoiceInterviewMessageRepository messages, VoiceInterviewEvaluationRepository reports,
        RedissonClient redis, LlmProviderRegistry providers) {
      return new VoiceInterviewService(sessions, messages, reports, redis, new VoiceInterviewProperties(),
          mock(VoiceEvaluateStreamProducer.class), providers);
    }
    @Bean VoiceInterviewEvaluationService evaluation(UnifiedEvaluationService model,
        LlmProviderRegistry providers, VoiceInterviewEvaluationRepository reports,
        VoiceInterviewMessageRepository messages, VoiceInterviewSessionRepository sessions,
        InterviewSkillService skills, VoiceInterviewEvaluationPersistenceService persistence) {
      return new VoiceInterviewEvaluationService(model, providers, reports, messages, sessions,
          new ObjectMapper(), skills, persistence);
    }
    @Bean VoiceInterviewEvaluationPersistenceService persistence(VoiceInterviewSessionRepository sessions,
        VoiceInterviewEvaluationRepository reports, VoiceInterviewService voice) {
      return new VoiceInterviewEvaluationPersistenceService(sessions, reports, new ObjectMapper(), voice);
    }
  }
}
