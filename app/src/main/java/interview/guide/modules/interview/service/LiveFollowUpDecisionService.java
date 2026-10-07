package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.QuestionEvaluationGuide;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.LiveFollowUpDecision;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 不调用模型的 LIVE 追问观察策略。
 *
 * <p>它只根据本题已持久化的 Rubric 与相邻预生成追问给出建议，避免把重型 RAG 或 LLM
 * 判断放进逐题提交路径。真正跳题须在独立固定集验证后再启用。</p>
 */
@Service
public class LiveFollowUpDecisionService {

    public LiveFollowUpDecision decide(InterviewQuestionDTO parent, String answer,
                                       List<InterviewQuestionDTO> questions, int parentPosition,
                                       int minimumAnswerCharacters, int minimumKeyPointHits) {
        if (parent == null || questions == null) {
            return notApplicable();
        }
        int nextPosition = parentPosition + 1;
        if (parentPosition < 0 || nextPosition >= questions.size()) {
            return notApplicable();
        }
        InterviewQuestionDTO followUp = questions.get(nextPosition);
        if (!followUp.isFollowUp() || !Integer.valueOf(parent.questionIndex()).equals(followUp.parentQuestionIndex())) {
            return notApplicable();
        }

        List<String> keyPoints = parent.evaluationGuide() == null
            ? List.of() : parent.evaluationGuide().keyPoints();
        String normalizedAnswer = normalize(answer);
        int matchedKeyPoints = (int) keyPoints.stream()
            .map(this::normalize)
            .filter(keyPoint -> !keyPoint.isEmpty() && normalizedAnswer.contains(keyPoint))
            .count();
        boolean enoughLength = normalizedAnswer.length() >= Math.max(1, minimumAnswerCharacters);
        boolean enoughEvidence = !keyPoints.isEmpty()
            && matchedKeyPoints >= Math.min(Math.max(1, minimumKeyPointHits), keyPoints.size());
        boolean relevant = sameCompetency(parent.evaluationGuide(), followUp.evaluationGuide(),
            parent.category(), followUp.category());
        boolean duplicate = isDuplicateOfEarlierQuestion(followUp, questions, parentPosition);
        LiveFollowUpDecision.Action action = enoughLength && enoughEvidence && relevant && !duplicate
            ? LiveFollowUpDecision.Action.ADVANCE : LiveFollowUpDecision.Action.DEEPEN;
        return new LiveFollowUpDecision(
            action, followUp.questionIndex(), matchedKeyPoints, keyPoints.size(), relevant, duplicate);
    }

    private LiveFollowUpDecision notApplicable() {
        return new LiveFollowUpDecision(LiveFollowUpDecision.Action.NOT_APPLICABLE,
            null, 0, 0, false, false);
    }

    private boolean sameCompetency(QuestionEvaluationGuide parentGuide,
                                   QuestionEvaluationGuide followUpGuide,
                                   String parentCategory, String followUpCategory) {
        String parentCompetency = parentGuide == null ? normalize(parentCategory) : normalize(parentGuide.competency());
        String followUpCompetency = followUpGuide == null ? normalize(followUpCategory) : normalize(followUpGuide.competency());
        return !parentCompetency.isEmpty() && parentCompetency.equals(followUpCompetency);
    }

    private boolean isDuplicateOfEarlierQuestion(InterviewQuestionDTO followUp,
                                                  List<InterviewQuestionDTO> questions, int parentPosition) {
        String normalizedFollowUp = normalize(followUp.question());
        if (normalizedFollowUp.isEmpty()) {
            return false;
        }
        return questions.stream()
            .limit(parentPosition)
            .map(InterviewQuestionDTO::question)
            .map(this::normalize)
            .anyMatch(normalizedFollowUp::equals);
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }
}
