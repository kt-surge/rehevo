package interview.guide.modules.voiceinterview.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Voice evaluation detail DTO — aligned with text-based interview InterviewDetailDTO.
 * This allows the frontend to reuse InterviewDetailPanel for rendering.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VoiceEvaluationDetailDTO {

    private Long sessionId;
    private int totalQuestions;
    private int answeredQuestions;
    private int scoredQuestions;
    private int failedQuestions;
    private int evidenceSupportedQuestions;
    private double evaluationCoverage;
    private double evidenceCoverage;
    private int overallScore;
    private String overallFeedback;
    private List<String> strengths;
    private List<String> improvements;
    private List<TrainingTask> trainingTasks;
    private List<AnswerDetail> answers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrainingTask {
        private String competency;
        private List<Integer> questionIndexes;
        private String reason;
        private String action;
        private String completionCriteria;
        private int priority;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AnswerDetail {
        private int questionIndex;
        private String question;
        private String category;
        private String userAnswer;
        private int score;
        private String feedback;
        private String evaluationStatus;
        private int rubricLevel;
        private List<String> answerEvidence;
        private List<String> missingPoints;
        private List<String> factualRisks;
        private String nextAction;
        private String referenceAnswer;
        private List<String> keyPoints;
    }
}
