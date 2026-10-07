package interview.guide.modules.voiceinterview.service;

import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** 提交期间重新检查会话，将报告和完成状态一起提交；模型调用由外部编排。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceInterviewEvaluationPersistenceService {
  private final VoiceInterviewSessionRepository sessionRepository;
  private final VoiceInterviewEvaluationRepository evaluationRepository;
  private final ObjectMapper objectMapper;
  private final VoiceInterviewService voiceInterviewService;

  @Transactional(rollbackFor = Exception.class)
  public boolean saveEvaluation(Long sessionId, EvaluationReport report) {
    var session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
    if (session == null) {
      return false;
    }
    var existing = evaluationRepository.findBySessionId(sessionId);
    if (session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED && existing.isPresent()) {
      return true;
    }
    var entity = existing.orElseGet(() -> VoiceInterviewEvaluationEntity.builder()
        .sessionId(sessionId).build());
    try {
      if (report == null) {
        fillEmptyReport(entity);
      } else {
        fillReport(entity, report);
      }
    } catch (JacksonException error) {
      log.error("序列化语音评估报告失败: sessionId={}", sessionId, error);
      throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED, "序列化评估报告失败");
    }
    entity.setInterviewerRole(session.getRoleType());
    entity.setInterviewDate(session.getStartTime());
    evaluationRepository.save(entity);
    session.setEvaluateStatus(AsyncTaskStatus.COMPLETED);
    session.setEvaluateError(null);
    sessionRepository.save(session);
    voiceInterviewService.invalidateSessionCacheAfterCommit(sessionId);
    return true;
  }

  private void fillReport(VoiceInterviewEvaluationEntity entity, EvaluationReport report) {
    entity.setOverallScore(report.overallScore());
    entity.setAnsweredQuestions(report.answeredQuestions());
    entity.setScoredQuestions(report.scoredQuestions());
    entity.setFailedQuestions(report.failedQuestions());
    entity.setEvidenceSupportedQuestions(report.evidenceSupportedQuestions());
    entity.setEvaluationCoverage(report.evaluationCoverage());
    entity.setEvidenceCoverage(report.evidenceCoverage());
    entity.setOverallFeedback(report.overallFeedback());
    entity.setQuestionEvaluationsJson(objectMapper.writeValueAsString(report.questionDetails()));
    entity.setStrengthsJson(objectMapper.writeValueAsString(report.strengths()));
    entity.setImprovementsJson(objectMapper.writeValueAsString(report.improvements()));
    entity.setTrainingTasksJson(objectMapper.writeValueAsString(report.trainingTasks()));
    entity.setReferenceAnswersJson(objectMapper.writeValueAsString(report.referenceAnswers()));
  }

  private void fillEmptyReport(VoiceInterviewEvaluationEntity entity) {
    entity.setOverallScore(0);
    entity.setAnsweredQuestions(0);
    entity.setScoredQuestions(0);
    entity.setFailedQuestions(0);
    entity.setEvidenceSupportedQuestions(0);
    entity.setEvaluationCoverage(0.0);
    entity.setEvidenceCoverage(0.0);
    entity.setOverallFeedback("本次语音面试未形成有效对话记录，暂无可评估内容。");
    entity.setQuestionEvaluationsJson("[]");
    entity.setStrengthsJson("[]");
    entity.setImprovementsJson("[\"请先完成至少一轮有效问答后再生成评估。\"]");
    entity.setTrainingTasksJson("[]");
    entity.setReferenceAnswersJson("[]");
  }
}
