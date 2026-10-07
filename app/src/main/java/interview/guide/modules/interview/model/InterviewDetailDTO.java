package interview.guide.modules.interview.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试详情DTO
 */
public record InterviewDetailDTO(
    Long id,
    String sessionId,
    Integer totalQuestions,
    String status,
    String evaluateStatus,
    String evaluateError,
    Integer overallScore,
    Integer answeredQuestions,
    Integer scoredQuestions,
    Integer failedQuestions,
    Integer evidenceSupportedQuestions,
    Double evaluationCoverage,
    Double evidenceCoverage,
    String overallFeedback,
    LocalDateTime createdAt,
    LocalDateTime completedAt,
    List<Object> questions,
    List<String> strengths,
    List<String> improvements,
    List<Object> trainingTasks,
    List<Object> referenceAnswers,
    List<AnswerDetailDTO> answers
) {
    /**
     * 答案详情DTO
     */
    public record AnswerDetailDTO(
        Integer questionIndex,
        String question,
        String category,
        String userAnswer,
        Integer score,
        String feedback,
        String evaluationStatus,
        Integer rubricLevel,
        List<String> answerEvidence,
        List<String> missingPoints,
        List<String> factualRisks,
        String nextAction,
        String referenceAnswer,
        List<String> keyPoints,
        LocalDateTime answeredAt
    ) {}
}
