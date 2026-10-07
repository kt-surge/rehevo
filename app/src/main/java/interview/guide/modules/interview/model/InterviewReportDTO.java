package interview.guide.modules.interview.model;

import java.util.List;

/**
 * 面试评估报告
 */
public record InterviewReportDTO(
    String sessionId,
    int totalQuestions,
    int answeredQuestions,
    int scoredQuestions,
    int failedQuestions,
    int evidenceSupportedQuestions,
    double evaluationCoverage,
    double evidenceCoverage,
    int overallScore,                          // 总分 (0-100)
    List<CategoryScore> categoryScores,        // 各类别得分
    List<QuestionEvaluation> questionDetails,  // 每题详情
    String overallFeedback,                    // 总体评价
    List<String> strengths,                    // 优势
    List<String> improvements,                 // 改进建议
    List<TrainingTask> trainingTasks,          // 下一轮训练任务
    List<ReferenceAnswer> referenceAnswers     // 参考答案
) {
    /**
     * 类别得分
     */
    public record CategoryScore(
        String category,
        int score,
        int questionCount,
        int answeredQuestionCount,
        int scoredQuestionCount,
        double evaluationCoverage
    ) {}
    
    /**
     * 问题评估详情
     */
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
        String evaluationStatus
    ) {}
    
    /**
     * 参考答案
     */
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
