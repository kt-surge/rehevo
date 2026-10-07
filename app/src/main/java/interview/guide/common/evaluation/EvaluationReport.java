package interview.guide.common.evaluation;

import java.util.List;

/**
 * 通用面试评估报告（文字面试和语音面试共用）
 */
public record EvaluationReport(
    String sessionId,
    int totalQuestions,
    int answeredQuestions,
    int scoredQuestions,
    int failedQuestions,
    int evidenceSupportedQuestions,
    double evaluationCoverage,
    double evidenceCoverage,
    int overallScore,
    List<CategoryScore> categoryScores,
    List<QuestionEvaluation> questionDetails,
    String overallFeedback,
    List<String> strengths,
    List<String> improvements,
    List<TrainingTask> trainingTasks,
    List<ReferenceAnswer> referenceAnswers
) {
    public enum EvaluationStatus {
        SCORED,
        UNANSWERED,
        EVALUATION_FAILED
    }

    public record CategoryScore(
        String category,
        int score,
        int questionCount,
        int answeredQuestionCount,
        int scoredQuestionCount,
        double evaluationCoverage
    ) {}

    public record QuestionEvaluation(
        int questionIndex,
        String question,
        String category,
        String userAnswer,
        int score,
        String feedback,
        int rubricLevel,
        List<String> answerEvidence,
        List<String> missingPoints,
        List<String> factualRisks,
        String nextAction,
        EvaluationStatus evaluationStatus
    ) {}

    public record ReferenceAnswer(
        int questionIndex,
        String question,
        String referenceAnswer,
        List<String> keyPoints
    ) {}

    public record TrainingTask(
        String competency,
        List<Integer> questionIndexes,
        String reason,
        String action,
        String completionCriteria,
        int priority
    ) {}
}
