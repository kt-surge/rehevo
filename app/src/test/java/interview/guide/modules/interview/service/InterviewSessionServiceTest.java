package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.evaluation.QuestionEvaluationGuide;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.infrastructure.redis.InterviewSessionCache.CachedSession;
import interview.guide.modules.interview.listener.EvaluateStreamProducer;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.TrainingTask;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("面试会话自适应 PREP")
class InterviewSessionServiceTest {

    @Test
    @DisplayName("创建下一场时把同范围历史训练任务传给出题服务")
    @SuppressWarnings("unchecked")
    void passesPriorTrainingTasksToQuestionGeneration() {
        InterviewQuestionService questionService = mock(InterviewQuestionService.class);
        InterviewPersistenceService persistenceService = mock(InterviewPersistenceService.class);
        TrainingTask task = new TrainingTask(
            "事务边界", List.of(2), "未讲清代理边界", "补充示例", "可解释传播语义", 3
        );
        QuestionEvaluationGuide guide = new QuestionEvaluationGuide(
            "事务边界", List.of("代理调用"), "", "SKILL_REFERENCE", List.of()
        );
        List<InterviewQuestionDTO> generated = List.of(
            InterviewQuestionDTO.create(0, "解释自调用", "SPRING", "Spring", "", false, null, guide)
        );
        when(persistenceService.getHistoricalQuestions("java-backend", 42L)).thenReturn(List.of());
        when(persistenceService.getRecentTrainingTasks("java-backend", 42L)).thenReturn(List.of(task));
        when(questionService.generateQuestionsBySkill(
            any(), anyString(), anyString(), any(), anyInt(), any(), any(), any(), any()
        )).thenReturn(generated);

        InterviewSessionService service = new InterviewSessionService(
            questionService,
            mock(AnswerEvaluationService.class),
            persistenceService,
            mock(InterviewSessionCache.class),
            new ObjectMapper(),
            mock(EvaluateStreamProducer.class),
            mock(LlmProviderRegistry.class),
            new InterviewPlanService(),
            mock(ApplicationMetrics.class),
            new LiveFollowUpDecisionService(),
            new InterviewQuestionProperties()
        );
        CreateInterviewRequest request = new CreateInterviewRequest(
            "候选人简历", 3, 42L, true, null, "java-backend", "mid", List.<CategoryDTO>of(), null
        );

        service.createSession(request);

        ArgumentCaptor<List<TrainingTask>> captor = ArgumentCaptor.forClass(List.class);
        verify(questionService).generateQuestionsBySkill(
            any(), anyString(), anyString(), any(), anyInt(), any(), captor.capture(), any(), any()
        );
        assertThat(captor.getValue()).containsExactly(task);
    }

    @Test
    @DisplayName("提交主问题时返回 LIVE 追问观察建议并记录有限指标")
    void returnsObservedLiveFollowUpDecisionAfterSubmittingAnswer() {
        InterviewQuestionService questionService = mock(InterviewQuestionService.class);
        InterviewPersistenceService persistenceService = mock(InterviewPersistenceService.class);
        InterviewSessionCache sessionCache = mock(InterviewSessionCache.class);
        ApplicationMetrics metrics = mock(ApplicationMetrics.class);
        ObjectMapper objectMapper = new ObjectMapper();
        QuestionEvaluationGuide guide = QuestionEvaluationGuide.standard(
            "缓存一致性", List.of("更新数据库", "删除缓存"), "追问失败补偿", "FIXTURE");
        List<InterviewQuestionDTO> questions = List.of(
            InterviewQuestionDTO.create(0, "如何保证缓存与数据库一致性？", "CACHE", "缓存一致性",
                null, false, null, guide),
            InterviewQuestionDTO.create(1, "请说明删除缓存失败后的补偿策略。", "CACHE", "缓存一致性",
                null, true, 0, guide)
        );
        CachedSession cachedSession = new CachedSession(
            "live-observe", "", null, "java-backend", "mid", questions, null, 0,
            interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus.IN_PROGRESS, objectMapper);
        when(sessionCache.getSession("live-observe")).thenReturn(Optional.of(cachedSession));

        InterviewSessionService service = new InterviewSessionService(
            questionService,
            mock(AnswerEvaluationService.class),
            persistenceService,
            sessionCache,
            objectMapper,
            mock(EvaluateStreamProducer.class),
            mock(LlmProviderRegistry.class),
            new InterviewPlanService(),
            metrics,
            new LiveFollowUpDecisionService(),
            new InterviewQuestionProperties()
        );

        var response = service.submitAnswer(new SubmitAnswerRequest(
            "live-observe", 0,
            "我会先更新数据库再删除缓存，并为删除失败设置重试和消息补偿，避免旧值长期残留，同时保留告警记录便于追溯。"
        ));

        assertThat(response.nextQuestion().questionIndex()).isEqualTo(1);
        assertThat(response.liveFollowUpDecision().action().name()).isEqualTo("ADVANCE");
        assertThat(response.liveFollowUpDecision().followUpRelevant()).isTrue();
        verify(metrics).recordInterviewLiveFollowUp("advance", true, false);
    }
}
