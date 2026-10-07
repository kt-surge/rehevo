package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.EvaluationReport.EvaluationStatus;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO.AnswerDetail;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO.TrainingTask;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 语音面试评估服务
 * 复用 UnifiedEvaluationService 的分批评估 + 结构化输出 + 降级兜底
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class VoiceInterviewEvaluationService {

    private final UnifiedEvaluationService unifiedEvaluationService;
    private final LlmProviderRegistry llmProviderRegistry;
    private final VoiceInterviewEvaluationRepository evaluationRepository;
    private final VoiceInterviewMessageRepository messageRepository;
    private final VoiceInterviewSessionRepository sessionRepository;
    private final ObjectMapper objectMapper;
    private final InterviewSkillService skillService;
    private final VoiceInterviewEvaluationPersistenceService persistenceService;

    /**
     * 生成语音面试评估（由异步消费者调用）
     * LLM 调用在事务外执行，仅 DB 写入在事务内
     */
    public boolean generateEvaluation(Long sessionId) {
        try {
            log.info("开始生成语音面试评估: sessionId={}", sessionId);

            VoiceInterviewSessionEntity session = sessionRepository.findById(sessionId).orElse(null);
            if (session == null) {
              return false;
            }
            if (session.getEvaluateStatus() == AsyncTaskStatus.COMPLETED
                && evaluationRepository.findBySessionId(sessionId).isPresent()) {
              return true;
            }
            List<VoiceInterviewMessageEntity> messages = messageRepository
                .findBySessionIdOrderBySequenceNumAsc(sessionId);

            if (messages.isEmpty()) {
                log.warn("语音面试会话无对话记录，生成空评估结果: sessionId={}", sessionId);
                return persistenceService.saveEvaluation(sessionId, null);
            }

            List<QaRecord> qaRecords = buildQaRecords(messages);

            String provider = session.getLlmProvider();
            ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(provider, LlmProviderRegistry.ToolAccess.NONE);

            String sessionIdStr = String.valueOf(sessionId);
            String referenceContext = skillService.buildEvaluationReferenceSectionSafe(session.getSkillId());
            EvaluationReport report = unifiedEvaluationService.evaluate(
                chatClient, sessionIdStr, qaRecords, null, referenceContext);

            return persistenceService.saveEvaluation(sessionId, report);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("生成语音面试评估失败: sessionId={}", sessionId, e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "生成评估失败: " + e.getMessage());
        }
    }

    public VoiceEvaluationDetailDTO getEvaluation(Long sessionId) {
        VoiceInterviewEvaluationEntity evaluation = evaluationRepository.findBySessionId(sessionId)
            .orElseThrow(() -> new BusinessException(ErrorCode.VOICE_EVALUATION_NOT_FOUND,
                "评估结果不存在: " + sessionId));

        return buildDetailDTO(evaluation);
    }

    private List<QaRecord> buildQaRecords(List<VoiceInterviewMessageEntity> messages) {
        List<QaRecord> records = new ArrayList<>();
        int index = 0;
        PendingQuestion pendingQuestion = null;

        for (VoiceInterviewMessageEntity msg : messages) {
            String aiText = VoiceInterviewMessageEntity.trimToNull(msg.getAiGeneratedText());
            String userText = VoiceInterviewMessageEntity.trimToNull(msg.getUserRecognizedText());

            if (pendingQuestion != null && userText != null) {
                records.add(new QaRecord(
                    index,
                    pendingQuestion.question(),
                    pendingQuestion.category(),
                    userText
                ));
                index++;
                pendingQuestion = null;
                if (aiText != null) {
                    pendingQuestion = new PendingQuestion(aiText, inferCategory(aiText));
                }
                continue;
            }

            if (pendingQuestion != null) {
                records.add(new QaRecord(
                    index,
                    pendingQuestion.question(),
                    pendingQuestion.category(),
                    null
                ));
                index++;
                pendingQuestion = null;
            }

            if (aiText != null && userText != null) {
                records.add(new QaRecord(index, aiText, inferCategory(aiText), userText));
                index++;
            } else if (aiText != null) {
                pendingQuestion = new PendingQuestion(aiText, inferCategory(aiText));
            } else if (userText != null) {
                records.add(new QaRecord(index, "", "综合", userText));
                index++;
            }
        }

        if (pendingQuestion != null) {
            records.add(new QaRecord(
                index,
                pendingQuestion.question(),
                pendingQuestion.category(),
                null
            ));
        }

        return records;
    }

    private record PendingQuestion(String question, String category) {}

    private String inferCategory(String aiText) {
        if (aiText == null) return "综合";
        if (aiText.contains("项目") || aiText.contains("实习") || aiText.contains("工作经历")) return "项目深挖";
        if (aiText.contains("自我介绍") || aiText.contains("介绍一下自己")) return "自我介绍";
        if (aiText.contains("职业规划") || aiText.contains("为什么") || aiText.contains("优缺点")) return "HR问题";
        return "技术问题";
    }

    private VoiceEvaluationDetailDTO buildDetailDTO(VoiceInterviewEvaluationEntity entity) {
        try {
            List<EvaluationReport.QuestionEvaluation> questionItems = objectMapper.readValue(
                entity.getQuestionEvaluationsJson(),
                new TypeReference<List<EvaluationReport.QuestionEvaluation>>() {}
            );

            List<String> strengths = objectMapper.readValue(
                entity.getStrengthsJson(),
                new TypeReference<List<String>>() {}
            );

            List<String> improvements = objectMapper.readValue(
                entity.getImprovementsJson(),
                new TypeReference<List<String>>() {}
            );

            List<EvaluationReport.TrainingTask> reportTasks = entity.getTrainingTasksJson() == null
                ? List.of()
                : objectMapper.readValue(
                    entity.getTrainingTasksJson(),
                    new TypeReference<List<EvaluationReport.TrainingTask>>() {}
                );

            List<EvaluationReport.ReferenceAnswer> refAnswers = objectMapper.readValue(
                entity.getReferenceAnswersJson(),
                new TypeReference<List<EvaluationReport.ReferenceAnswer>>() {}
            );

            Map<Integer, EvaluationReport.ReferenceAnswer> refMap = refAnswers.stream()
                .collect(Collectors.toMap(
                    EvaluationReport.ReferenceAnswer::questionIndex, r -> r, (a, b) -> a));

            List<AnswerDetail> answers = new ArrayList<>();
            for (EvaluationReport.QuestionEvaluation q : questionItems) {
                EvaluationReport.ReferenceAnswer ref = refMap.get(q.questionIndex());
                answers.add(AnswerDetail.builder()
                    .questionIndex(q.questionIndex())
                    .question(q.question())
                    .category(q.category())
                    .userAnswer(q.userAnswer())
                    .score(q.score())
                    .feedback(q.feedback())
                    .evaluationStatus(resolveStatus(q).name())
                    .rubricLevel(q.rubricLevel())
                    .answerEvidence(q.answerEvidence())
                    .missingPoints(q.missingPoints())
                    .factualRisks(q.factualRisks())
                    .nextAction(q.nextAction())
                    .referenceAnswer(ref != null ? ref.referenceAnswer() : null)
                    .keyPoints(ref != null ? ref.keyPoints() : null)
                    .build());
            }

            return VoiceEvaluationDetailDTO.builder()
                .sessionId(entity.getSessionId())
                .totalQuestions(answers.size())
                .answeredQuestions((int) questionItems.stream()
                    .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
                    .count())
                .scoredQuestions((int) questionItems.stream()
                    .filter(q -> resolveStatus(q) == EvaluationStatus.SCORED)
                    .count())
                .failedQuestions((int) questionItems.stream()
                    .filter(q -> resolveStatus(q) == EvaluationStatus.EVALUATION_FAILED)
                    .count())
                .evidenceSupportedQuestions((int) questionItems.stream()
                    .filter(q -> resolveStatus(q) == EvaluationStatus.SCORED)
                    .filter(q -> q.answerEvidence() != null && !q.answerEvidence().isEmpty())
                    .count())
                .evaluationCoverage(calculateCoverage(questionItems))
                .evidenceCoverage(calculateEvidenceCoverage(questionItems))
                .overallScore(entity.getOverallScore())
                .overallFeedback(entity.getOverallFeedback())
                .strengths(strengths)
                .improvements(improvements)
                .trainingTasks(reportTasks.stream()
                    .map(task -> TrainingTask.builder()
                        .competency(task.competency())
                        .questionIndexes(task.questionIndexes())
                        .reason(task.reason())
                        .action(task.action())
                        .completionCriteria(task.completionCriteria())
                        .priority(task.priority())
                        .build())
                    .toList())
                .answers(answers)
                .build();

        } catch (Exception e) {
            log.error("构建评估详情失败: sessionId={}", entity.getSessionId(), e);
            throw new BusinessException(ErrorCode.VOICE_EVALUATION_FAILED,
                "构建评估结果失败: " + e.getMessage());
        }
    }

    private EvaluationStatus resolveStatus(EvaluationReport.QuestionEvaluation question) {
        if (question.evaluationStatus() != null) {
            return question.evaluationStatus();
        }
        return question.userAnswer() == null || question.userAnswer().isBlank()
            ? EvaluationStatus.UNANSWERED
            : EvaluationStatus.SCORED;
    }

    private double calculateCoverage(List<EvaluationReport.QuestionEvaluation> questions) {
        long answered = questions.stream()
            .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
            .count();
        if (answered == 0) return 0.0;
        long scored = questions.stream()
            .filter(q -> resolveStatus(q) == EvaluationStatus.SCORED)
            .count();
        return (double) scored / answered;
    }

    private double calculateEvidenceCoverage(List<EvaluationReport.QuestionEvaluation> questions) {
        long scored = questions.stream()
            .filter(q -> resolveStatus(q) == EvaluationStatus.SCORED)
            .count();
        if (scored == 0) return 0.0;
        long supported = questions.stream()
            .filter(q -> resolveStatus(q) == EvaluationStatus.SCORED)
            .filter(q -> q.answerEvidence() != null && !q.answerEvidence().isEmpty())
            .count();
        return (double) supported / scored;
    }

}
