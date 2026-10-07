package interview.guide.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.infrastructure.mapper.KnowledgeBaseMapper;
import interview.guide.infrastructure.mapper.RagChatMapper;
import interview.guide.modules.knowledgebase.model.RagChatMessageEntity;
import interview.guide.modules.knowledgebase.model.RagChatSessionEntity;
import interview.guide.modules.knowledgebase.model.RagGenerationState;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.repository.RagChatMessageRepository;
import interview.guide.modules.knowledgebase.repository.RagChatSessionRepository;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@Tag("integration")
@SpringJUnitConfig(RagChatPersistenceIntegrationTest.Config.class)
@DisplayName("H2 真实事务验证 RAG 终态与完整问答历史，不调用模型")
class RagChatPersistenceIntegrationTest {
  @Autowired private RagChatSessionRepository sessions;
  @Autowired private RagChatMessageRepository messages;
  @Autowired private RagChatSessionService service;
  @Autowired private PlatformTransactionManager transactions;
  private Long sessionId;

  @BeforeEach
  void createOwnSession() {
    var session = new RagChatSessionEntity();
    session.setTitle("controlled RAG terminal test");
    sessionId = sessions.saveAndFlush(session).getId();
  }

  @Test
  @DisplayName("取消后的迟到完成不覆盖正文、状态或 completed，当前孤立问题不会进入历史")
  void terminalAndHistory() {
    Long cancelled = service.prepareStreamMessage(sessionId, "取消问题");
    service.finishStreamMessage(cancelled, "取消前缀", List.of(), RagGenerationState.CANCELLED, "USER_CANCELLED");
    assertThat(service.finishStreamMessage(cancelled, "迟到覆盖", List.of(), RagGenerationState.COMPLETED, null))
        .isEqualTo(RagGenerationState.CANCELLED);
    var loaded = messages.findById(cancelled).orElseThrow();
    assertThat(loaded.getContent()).isEqualTo("取消前缀");
    assertThat(loaded.getCompleted()).isFalse();
    Long success = service.prepareStreamMessage(sessionId, "成功问题");
    service.finishStreamMessage(success, "成功回答", List.of(), RagGenerationState.COMPLETED, null);
    service.prepareStreamMessage(sessionId, "当前未回答问题");
    var history = messages.findRecentCompletedBySessionId(sessionId, PageRequest.of(0, 10));
    assertThat(history).extracting(RagChatMessageEntity::getContent).containsExactly("成功回答", "成功问题");
  }

  @Test
  @DisplayName("删除后的迟到回调不复活消息")
  void deletedMessage() {
    Long messageId = service.prepareStreamMessage(sessionId, "将被删除的问题");
    service.deleteSession(sessionId);
    assertThat(service.finishStreamMessage(messageId, "迟到", List.of(), RagGenerationState.COMPLETED, null)).isNull();
    assertThat(messages.findById(messageId)).isEmpty();
  }

  @Test
  @DisplayName("并发准备使用会话行锁，消息顺序没有重复或丢失")
  void concurrentPreparation() throws Exception {
    var gate = new CountDownLatch(1);
    var error = new AtomicReference<Throwable>();
    var first = Thread.ofVirtual().start(() -> prepareAfterGate(gate, error));
    var second = Thread.ofVirtual().start(() -> prepareAfterGate(gate, error));
    gate.countDown(); first.join(); second.join();
    assertThat(error.get()).isNull();
    assertThat(messages.findBySessionIdOrderByMessageOrderAsc(sessionId))
        .extracting(RagChatMessageEntity::getMessageOrder).containsExactly(0, 1, 2, 3);
    assertThat(sessions.findById(sessionId).orElseThrow().getMessageCount()).isEqualTo(4);
  }

  @Test
  @DisplayName("事务被数据库拒绝时终态更新回滚，不残留假成功")
  void rollback() {
    Long messageId = service.prepareStreamMessage(sessionId, "回滚问题");
    var template = new TransactionTemplate(transactions);
    template.executeWithoutResult(status -> {
      service.finishStreamMessage(messageId, "事务内正文", List.of(), RagGenerationState.COMPLETED, null);
      status.setRollbackOnly();
    });
    var loaded = messages.findById(messageId).orElseThrow();
    assertThat(loaded.getGenerationState()).isEqualTo(RagGenerationState.GENERATING);
    assertThat(loaded.getCompleted()).isFalse();
    assertThat(loaded.getContent()).isEmpty();
  }

  private void prepareAfterGate(CountDownLatch gate, AtomicReference<Throwable> error) {
    try {
      if (!gate.await(5, TimeUnit.SECONDS)) throw new AssertionError("gate timeout");
      service.prepareStreamMessage(sessionId, "并发公开问题");
    } catch (Exception failure) { error.compareAndSet(null, failure); }
  }

  @Configuration
  @EnableTransactionManagement
  @EnableJpaRepositories(basePackageClasses = RagChatMessageRepository.class)
  static class Config {
    @Bean DataSource dataSource() {
      return new DriverManagerDataSource("jdbc:h2:mem:rag-terminal;DB_CLOSE_DELAY=-1", "sa", "");
    }
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
      var factory = new LocalContainerEntityManagerFactoryBean();
      factory.setDataSource(dataSource);
      factory.setPackagesToScan("interview.guide.modules.knowledgebase.model");
      factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
      factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-drop", "hibernate.show_sql", "false"));
      return factory;
    }
    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
      return new JpaTransactionManager(factory);
    }
    @Bean RagChatSessionService service(RagChatSessionRepository sessions, RagChatMessageRepository messages,
        KnowledgeBaseRepository knowledgeBases) {
      return new RagChatSessionService(sessions, messages, knowledgeBases, mock(KnowledgeBaseQueryService.class),
          mock(RagChatMapper.class), mock(KnowledgeBaseMapper.class), new KnowledgeBaseQueryProperties(), new ObjectMapper());
    }
  }
}
