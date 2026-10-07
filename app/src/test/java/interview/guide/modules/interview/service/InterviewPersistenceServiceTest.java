package interview.guide.modules.interview.service;

import interview.guide.infrastructure.redis.InterviewSessionCache;
import interview.guide.modules.interview.model.InterviewReportDTO.TrainingTask;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("面试训练任务历史读取")
class InterviewPersistenceServiceTest {

    private final InterviewSessionRepository sessionRepository = mock(InterviewSessionRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final VoiceInterviewSessionRepository voiceSessions = mock(VoiceInterviewSessionRepository.class);
    private final VoiceInterviewEvaluationRepository voiceEvaluations = mock(VoiceInterviewEvaluationRepository.class);
    private final InterviewPersistenceService service = new InterviewPersistenceService(
        sessionRepository,
        mock(InterviewAnswerRepository.class),
        mock(ResumeRepository.class),
        objectMapper, voiceSessions, voiceEvaluations, mock(InterviewSessionCache.class)
    );

    @Test
    @DisplayName("按简历和 Skill 读取训练任务，并跳过损坏的历史 JSON")
    void loadsScopedTrainingTasksAndSkipsMalformedHistory() throws Exception {
        InterviewSessionEntity valid = new InterviewSessionEntity();
        valid.setTrainingTasksJson(objectMapper.writeValueAsString(List.of(new TrainingTask(
            "事务边界", List.of(1), "未讲清代理边界", "补充示例", "可解释传播语义", 3
        ))));
        InterviewSessionEntity malformed = new InterviewSessionEntity();
        malformed.setTrainingTasksJson("not-json");
        when(sessionRepository.findTop10ByResumeIdAndSkillIdAndStatusOrderByCreatedAtDesc(
            42L, "java-backend", InterviewSessionEntity.SessionStatus.EVALUATED))
            .thenReturn(List.of(valid, malformed));

        List<TrainingTask> tasks = service.getRecentTrainingTasks("java-backend", 42L);

        assertThat(tasks).singleElement()
            .extracting(TrainingTask::competency, TrainingTask::priority)
            .containsExactly("事务边界", 3);
    }

  @Test
  @DisplayName("只有语音报告时任务仍回流同岗位的文字训练")
  void voiceOnlyTasksReachTextTraining() throws Exception {
    when(sessionRepository.findTop10ByResumeIsNullAndSkillIdAndStatusOrderByCreatedAtDesc(
        "java-backend", InterviewSessionEntity.SessionStatus.EVALUATED))
        .thenReturn(List.of());
    when(voiceSessions.findTop10BySkillIdAndResumeIdAndEvaluateStatusOrderByCreatedAtDesc(
        "java-backend", null, AsyncTaskStatus.COMPLETED))
        .thenReturn(List.of(VoiceInterviewSessionEntity.builder().id(17L).build()));
    VoiceInterviewEvaluationEntity report = VoiceInterviewEvaluationEntity.builder()
        .sessionId(17L).trainingTasksJson(objectMapper.writeValueAsString(List.of(new TrainingTask(
            "事务边界", List.of(0), "未说明提交与回调的顺序", "补充失败用例",
            "解释提交前后可见性", 1)))).build();
    when(voiceEvaluations.findBySessionIdIn(List.of(17L))).thenReturn(List.of(report));

    assertThat(service.getRecentTrainingTasks("java-backend", null))
        .singleElement().extracting(TrainingTask::competency).isEqualTo("事务边界");
  }
}
