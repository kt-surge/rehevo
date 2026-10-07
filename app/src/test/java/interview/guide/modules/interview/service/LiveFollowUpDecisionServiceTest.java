package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.QuestionEvaluationGuide;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.LiveFollowUpDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Objects.requireNonNull;

@DisplayName("LIVE 追问观察策略")
class LiveFollowUpDecisionServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LiveFollowUpDecisionService service = new LiveFollowUpDecisionService();

    @Test
    @DisplayName("冻结样例按关键点、相关性和重复检测给出可复跑建议")
    void evaluatesFrozenLiveFollowUpCases() throws Exception {
        try (InputStream input = requireNonNull(getClass().getResourceAsStream(
            "/interview-adaptation/live-follow-up-v1.json"))) {
            Fixture fixture = objectMapper.readValue(input, Fixture.class);
            assertThat(fixture.version()).isEqualTo("live-follow-up-v1");
            int applicableCases = 0;
            int actionMatched = 0;
            int relevantFollowUps = 0;
            int duplicateFollowUps = 0;

            for (CaseFixture item : fixture.cases()) {
                List<InterviewQuestionDTO> questions = buildQuestions(item);
                int parentPosition = item.priorQuestions() == null ? 0 : item.priorQuestions().size();
                LiveFollowUpDecision decision = service.decide(
                    questions.get(parentPosition), item.answer(), questions, parentPosition, 40, 1);

                assertThat(decision.action().name()).as(item.id()).isEqualTo(item.expectedAction());
                assertThat(decision.followUpRelevant()).as(item.id()).isEqualTo(item.expectedRelevant());
                assertThat(decision.duplicateQuestion()).as(item.id()).isEqualTo(item.expectedDuplicate());
                if (decision.action() != LiveFollowUpDecision.Action.NOT_APPLICABLE) {
                    applicableCases++;
                    if ((item.referenceCanAdvance() && decision.action() == LiveFollowUpDecision.Action.ADVANCE)
                        || (!item.referenceCanAdvance() && decision.action() == LiveFollowUpDecision.Action.DEEPEN)) {
                        actionMatched++;
                    }
                    if (decision.followUpRelevant()) {
                        relevantFollowUps++;
                    }
                    if (decision.duplicateQuestion()) {
                        duplicateFollowUps++;
                    }
                }
            }
            assertThat(fixture.cases()).hasSize(5);
            assertThat(applicableCases).isEqualTo(4);
            assertThat(actionMatched).isEqualTo(4);
            assertThat(relevantFollowUps).isEqualTo(3);
            assertThat(duplicateFollowUps).isEqualTo(1);
        }
    }

    private List<InterviewQuestionDTO> buildQuestions(CaseFixture item) {
        List<InterviewQuestionDTO> questions = new ArrayList<>();
        List<String> previous = item.priorQuestions() == null ? List.of() : item.priorQuestions();
        for (int index = 0; index < previous.size(); index++) {
            questions.add(InterviewQuestionDTO.create(
                index, previous.get(index), "HISTORY", "历史题", null, false, null,
                QuestionEvaluationGuide.standard("历史题", List.of(), "", "FIXTURE")));
        }
        int parentIndex = questions.size();
        QuestionEvaluationGuide parentGuide = QuestionEvaluationGuide.standard(
            item.competency(), item.keyPoints(), "补充关键证据", "FIXTURE");
        questions.add(InterviewQuestionDTO.create(
            parentIndex, item.parentQuestion(), "CACHE", item.competency(), null, false, null, parentGuide));
        if (item.followUpQuestion() != null) {
            QuestionEvaluationGuide followUpGuide = QuestionEvaluationGuide.standard(
                item.followUpCompetency(), List.of(), "", "FIXTURE");
            questions.add(InterviewQuestionDTO.create(
                parentIndex + 1, item.followUpQuestion(), "CACHE", item.followUpCompetency(), null,
                true, parentIndex, followUpGuide));
        }
        return questions;
    }

    private record Fixture(String version, List<CaseFixture> cases) {
    }

    private record CaseFixture(
        String id,
        String parentQuestion,
        String competency,
        List<String> keyPoints,
        String answer,
        String followUpQuestion,
        String followUpCompetency,
        List<String> priorQuestions,
        String expectedAction,
        boolean referenceCanAdvance,
        boolean expectedRelevant,
        boolean expectedDuplicate
    ) {
    }
}
