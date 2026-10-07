package interview.guide.modules.interview.service;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.TrainingTask;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@Tag("integration")
@SpringJUnitConfig(InterviewTrainingHistoryIntegrationTest.Config.class)
@DisplayName("H2 真实 JPA 验证语音与文字训练任务回流，不调用模型")
class InterviewTrainingHistoryIntegrationTest {
  @Autowired private InterviewSessionRepository textSessions;
  @Autowired private VoiceInterviewSessionRepository voiceSessions;
  @Autowired private VoiceInterviewEvaluationRepository voiceEvaluations;
  @Autowired private ResumeRepository resumes;
  @Autowired private InterviewPersistenceService service;
  @Autowired private EntityManagerFactory factory;
  @Autowired private InterviewSessionCache cache;
  @Autowired private PlatformTransactionManager transactionManager;
  private final ObjectMapper mapper = new ObjectMapper();
  private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 10, 5, 12, 0);

  @BeforeEach
  void clearOwnInMemoryData() {
    voiceEvaluations.deleteAll();
    voiceSessions.deleteAll();
    textSessions.deleteAll();
    resumes.deleteAll();
    reset(cache);
  }

  @Test
  @DisplayName("真实代理事务提交删除后才失效单条缓存")
  void committedDeleteEvictsCache() throws Exception {
    var session = text("java-backend", null, "controlled-delete", 1);
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      service.deleteSessionBySessionId(session.getSessionId());
      verifyNoInteractions(cache);
    });
    assertThat(textSessions.findBySessionId(session.getSessionId())).isEmpty();
    verify(cache).deleteSession(session.getSessionId());
    verifyNoMoreInteractions(cache);
  }

  @Test
  @DisplayName("真实代理事务回滚恢复数据库会话并保留缓存")
  void rolledBackDeleteKeepsCache() throws Exception {
    var session = text("java-backend", null, "controlled-rollback", 1);
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      service.deleteSessionBySessionId(session.getSessionId());
      status.setRollbackOnly();
    });
    assertThat(textSessions.findBySessionId(session.getSessionId())).isPresent();
    verifyNoInteractions(cache);
  }

  @Test
  @DisplayName("简历批量删除真实提交只清理同一简历会话，保留其他记录")
  void bulkDeleteKeepsUnrelatedSession() throws Exception {
    var resume = resume("controlled-delete-resume");
    var first = text("java-backend", resume, "controlled-a", 1);
    var second = text("java-backend", resume, "controlled-b", 2);
    var other = text("java-backend", null, "controlled-other", 3);
    service.deleteSessionsByResumeId(resume.getId());
    assertThat(textSessions.findAll()).extracting(InterviewSessionEntity::getSessionId)
        .containsExactly(other.getSessionId());
    verify(cache).deleteSession(first.getSessionId());
    verify(cache).deleteSession(second.getSessionId());
    verifyNoMoreInteractions(cache);
  }

  @Test
  @DisplayName("只有语音任务也能进入文字复测计划，并且读取最多三次查询")
  void voiceTaskEntersTextPlan() throws Exception {
    voice("java-backend", null, AsyncTaskStatus.COMPLETED, "事务边界", 1);
    var statistics = factory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    List<TrainingTask> tasks = service.getRecentTrainingTasks("java-backend", null);
    assertThat(tasks).extracting(TrainingTask::competency).containsExactly("事务边界");
    assertThat(statistics.getQueryExecutionCount()).isLessThanOrEqualTo(3);
    var question = InterviewQuestionDTO.create(0, "如何验证提交后的可见性？", "TRANSACTION", "事务边界");
    var plan = new InterviewPlanService().build("java-backend", "mid", List.of(question), tasks);
    assertThat(plan.requestedFocusCompetencies()).isEqualTo(1);
    assertThat(plan.prioritizedCompetencies()).isEqualTo(1);
    assertThat(plan.retestCoverage()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("通用与简历模式精确隔离岗位、简历和失败或未完成评估")
  void scopesBothChannelsAndEvaluationState() throws Exception {
    ResumeEntity first = resume("controlled-a");
    ResumeEntity second = resume("controlled-b");
    text("java-backend", null, "generic-text", 1);
    voice("java-backend", null, AsyncTaskStatus.COMPLETED, "generic-voice", 2);
    text("java-backend", first, "first-text", 3);
    voice("java-backend", first.getId(), AsyncTaskStatus.COMPLETED, "first-voice", 4);
    text("java-backend", second, "other-resume-text", 5);
    voice("java-backend", second.getId(), AsyncTaskStatus.COMPLETED, "other-resume-voice", 6);
    text("frontend", null, "other-skill-text", 7);
    voice("frontend", null, AsyncTaskStatus.COMPLETED, "other-skill-voice", 8);
    voice("java-backend", null, AsyncTaskStatus.FAILED, "failed-voice", 9);
    voice("java-backend", first.getId(), AsyncTaskStatus.PROCESSING, "unfinished-voice", 10);
    var unfinished = text("java-backend", null, "unfinished-text", 11);
    unfinished.setStatus(InterviewSessionEntity.SessionStatus.CREATED);
    textSessions.saveAndFlush(unfinished);

    assertThat(service.getRecentTrainingTasks("java-backend", null))
        .extracting(TrainingTask::competency).containsExactly("generic-voice", "generic-text");
    assertThat(service.getRecentTrainingTasks("java-backend", first.getId()))
        .extracting(TrainingTask::competency).containsExactly("first-voice", "first-text");
  }

  @Test
  @DisplayName("按两路会话创建时间统一倒序，最新语音任务不被旧文字任务挤走")
  void mergesRecencyAcrossChannels() throws Exception {
    voice("java-backend", null, AsyncTaskStatus.COMPLETED, "old-voice", 1);
    text("java-backend", null, "middle-text", 2);
    voice("java-backend", null, AsyncTaskStatus.COMPLETED, "new-voice", 3);
    assertThat(service.getRecentTrainingTasks("java-backend", null))
        .extracting(TrainingTask::competency).containsExactly("new-voice", "middle-text", "old-voice");
  }

  @Test
  @DisplayName("坏 JSON、JSON null 和空任务不阻断其他场次，总返回不超过二十项")
  void skipsMalformedAndKeepsBound() throws Exception {
    var valid = text("java-backend", null, "placeholder", 1);
    valid.setTrainingTasksJson(mapper.writeValueAsString(IntStream.range(0, 25)
        .mapToObj(index -> task("bounded-" + index)).toList()));
    textSessions.saveAndFlush(valid);
    var broken = voice("java-backend", null, AsyncTaskStatus.COMPLETED, "bad", 2);
    broken.setTrainingTasksJson("not-json");
    voiceEvaluations.saveAndFlush(broken);
    var jsonNull = voice("java-backend", null, AsyncTaskStatus.COMPLETED, "null", 3);
    jsonNull.setTrainingTasksJson("null");
    voiceEvaluations.saveAndFlush(jsonNull);
    var empty = voice("java-backend", null, AsyncTaskStatus.COMPLETED, "empty", 4);
    empty.setTrainingTasksJson("[null,{\"competency\":\" \"}]");
    voiceEvaluations.saveAndFlush(empty);
    assertThat(service.getRecentTrainingTasks("java-backend", null))
        .extracting(TrainingTask::competency)
        .containsExactlyElementsOf(IntStream.range(0, 20).mapToObj(i -> "bounded-" + i).toList());
  }

  private TrainingTask task(String competency) {
    return new TrainingTask(competency, List.of(0), "受控能力缺口", "补充验证用例", "解释失败边界", 3);
  }

  private ResumeEntity resume(String hash) {
    var resume = new ResumeEntity();
    resume.setFileHash(hash);
    resume.setOriginalFilename(hash + ".txt");
    return resumes.saveAndFlush(resume);
  }

  private InterviewSessionEntity text(String skill, ResumeEntity resume, String competency, int time)
      throws Exception {
    var session = new InterviewSessionEntity();
    session.setSessionId(UUID.randomUUID().toString());
    session.setSkillId(skill);
    session.setResume(resume);
    session.setStatus(InterviewSessionEntity.SessionStatus.EVALUATED);
    session.setTrainingTasksJson(mapper.writeValueAsString(List.of(task(competency))));
    session = textSessions.saveAndFlush(session);
    session.setCreatedAt(BASE_TIME.plusMinutes(time));
    return textSessions.saveAndFlush(session);
  }

  private VoiceInterviewEvaluationEntity voice(String skill, Long resume, AsyncTaskStatus status,
      String competency, int time) throws Exception {
    var session = VoiceInterviewSessionEntity.builder().roleType(skill).skillId(skill)
        .resumeId(resume).status(VoiceInterviewSessionStatus.COMPLETED).evaluateStatus(status).build();
    session = voiceSessions.saveAndFlush(session);
    session.setCreatedAt(BASE_TIME.plusMinutes(time));
    session = voiceSessions.saveAndFlush(session);
    return voiceEvaluations.saveAndFlush(VoiceInterviewEvaluationEntity.builder()
        .sessionId(session.getId()).trainingTasksJson(mapper.writeValueAsString(List.of(task(competency))))
        .build());
  }

  @Configuration
  @EnableTransactionManagement
  @EnableJpaRepositories(basePackageClasses = {
      InterviewSessionRepository.class, VoiceInterviewSessionRepository.class, ResumeRepository.class})
  static class Config {
    @Bean DataSource dataSource() {
      return new DriverManagerDataSource(
          "jdbc:h2:mem:training-history;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan("interview.guide.modules.interview.model",
          "interview.guide.modules.voiceinterview.model", "interview.guide.modules.resume.model");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop",
          "hibernate.show_sql", "false", "hibernate.generate_statistics", "true"));
      return factory;
    }
    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }
    @Bean InterviewSessionCache sessionCache() {
      return mock(InterviewSessionCache.class);
    }
    @Bean InterviewPersistenceService service(InterviewSessionRepository text,
        InterviewAnswerRepository answers, ResumeRepository resumes,
        VoiceInterviewSessionRepository voice, VoiceInterviewEvaluationRepository evaluations,
        InterviewSessionCache cache) {
      return new InterviewPersistenceService(text, answers, resumes, new ObjectMapper(), voice, evaluations, cache);
    }
  }
}
