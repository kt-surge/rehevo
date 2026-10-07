package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.QuestionEvaluationGuide;
import interview.guide.modules.interview.model.InterviewPlan;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.TrainingTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

import static java.util.Objects.requireNonNull;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("面试 PREP 计划")
class InterviewPlanServiceTest {

    private final InterviewPlanService service = new InterviewPlanService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("按能力点聚合主问题并忽略追问")
    void groupsMainQuestionsByCompetency() {
        QuestionEvaluationGuide transactionGuide = new QuestionEvaluationGuide(
            "事务边界", List.of("代理调用", "传播语义"), "继续追问", "SKILL_REFERENCE", List.of()
        );
        List<InterviewQuestionDTO> questions = List.of(
            InterviewQuestionDTO.create(0, "什么是自调用问题？", "TECH", "Spring", "", false, null, transactionGuide),
            InterviewQuestionDTO.create(1, "REQUIRES_NEW 有何差异？", "TECH", "Spring", "", false, null, transactionGuide),
            InterviewQuestionDTO.create(2, "再举例说明", "FOLLOW_UP", "Spring", "", true, 1, transactionGuide)
        );

        InterviewPlan plan = service.build("java-backend", "mid", questions);

        assertThat(plan.plannedMainQuestions()).isEqualTo(2);
        assertThat(plan.plannedCompetencies()).isEqualTo(1);
        assertThat(plan.competencyCoverage()).isEqualTo(1.0);
        assertThat(plan.competencies().getFirst().questionIndexes()).containsExactly(0, 1);
        assertThat(plan.competencies().getFirst().evidenceChecklist()).containsExactly("代理调用", "传播语义");
    }

    @Test
    @DisplayName("上一场训练任务会提高同能力点的本场优先级")
    void prioritizesCompetencyFromTrainingTask() {
        QuestionEvaluationGuide guide = new QuestionEvaluationGuide(
            "事务边界", List.of("代理调用"), "", "SKILL_REFERENCE", List.of()
        );
        List<InterviewQuestionDTO> questions = List.of(
            InterviewQuestionDTO.create(0, "解释自调用", "TECH", "Spring", "", false, null, guide)
        );
        List<TrainingTask> priorTasks = List.of(new TrainingTask(
            "事务边界", List.of(2), "上一场未说明代理边界", "补充练习", "能说明代理调用", 3
        ));

        InterviewPlan plan = service.build("java-backend", "mid", questions, priorTasks);

        assertThat(plan.requestedFocusCompetencies()).isEqualTo(1);
        assertThat(plan.prioritizedCompetencies()).isEqualTo(1);
        assertThat(plan.retestCoverage()).isEqualTo(1.0);
        assertThat(plan.competencies().getFirst().priority()).isEqualTo(3);
        assertThat(plan.competencies().getFirst().priorityReasons()).containsExactly("上一场未说明代理边界");
    }

    @Test
    @DisplayName("最多取三个去重历史重点，计划分子不会超过分母")
    void capsDeduplicatedHistoricalFocusAtThree() {
        List<InterviewQuestionDTO> questions = List.of(
            question(0, "A"), question(1, "B"), question(2, "C"), question(3, "D")
        );
        List<TrainingTask> tasks = List.of(
            task("A", 4), task("A", 3), task("B", 3), task("C", 2), task("D", 1)
        );

        InterviewPlan plan = service.build("java-backend", "mid", questions, tasks);

        assertThat(plan.requestedFocusCompetencies()).isEqualTo(3);
        assertThat(plan.prioritizedCompetencies()).isEqualTo(3);
        assertThat(plan.retestCoverage()).isEqualTo(1.0);
        assertThat(plan.competencies()).extracting(InterviewPlan.CompetencyPlan::competency)
            .containsExactly("A", "B", "C", "D");
        assertThat(plan.competencies().getLast().priority()).isZero();
    }

    @Test
    @DisplayName("历史重点未全部出现在本场题单时，复测覆盖率如实反映缺口")
    void exposesPartialRetestCoverage() {
        List<InterviewQuestionDTO> questions = List.of(question(0, "事务边界"), question(1, "幂等消费"));
        List<TrainingTask> tasks = List.of(task("事务边界", 4), task("RAG 证据", 3));

        InterviewPlan plan = service.build("java-backend", "mid", questions, tasks);

        assertThat(plan.requestedFocusCompetencies()).isEqualTo(2);
        assertThat(plan.prioritizedCompetencies()).isEqualTo(1);
        assertThat(plan.retestCoverage()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("冻结跨场序列按历史重点覆盖口径生成 PREP 计划")
    void evaluatesFrozenRetestSequence() throws Exception {
        try (InputStream input = requireNonNull(getClass().getResourceAsStream(
            "/interview-adaptation/retest-sequence-v1.json"))) {
            RetestSequenceFixture fixture = objectMapper.readValue(input, RetestSequenceFixture.class);
            assertThat(fixture.version()).isEqualTo("retest-sequence-v1");
            assertThat(fixture.cases()).hasSize(4);
            for (RetestSequenceCase scenario : fixture.cases()) {
                List<InterviewQuestionDTO> questions = scenario.questionCompetencies().stream()
                    .map(competency -> question(scenario.questionCompetencies().indexOf(competency), competency))
                    .toList();
                List<TrainingTask> priorTasks = scenario.priorTrainingTasks().stream()
                    .map(task -> task(task.competency(), task.priority()))
                    .toList();

                InterviewPlan plan = service.build("java-backend", "mid", questions, priorTasks);

                assertThat(plan.requestedFocusCompetencies()).as(scenario.id())
                    .isEqualTo(scenario.expectedRequestedFocus());
                assertThat(plan.prioritizedCompetencies()).as(scenario.id())
                    .isEqualTo(scenario.expectedPrioritized());
                assertThat(plan.retestCoverage()).as(scenario.id())
                    .isEqualTo(scenario.expectedRetestCoverage());
            }
        }
    }

    private InterviewQuestionDTO question(int index, String competency) {
        return InterviewQuestionDTO.create(index, competency, "TECH", competency, "", false, null,
            new QuestionEvaluationGuide(competency, List.of(), "", "SKILL_REFERENCE", List.of()));
    }

    private TrainingTask task(String competency, int priority) {
        return new TrainingTask(competency, List.of(), competency, "", "", priority);
    }

    private record RetestSequenceFixture(String version, String scope, List<RetestSequenceCase> cases) {}

    private record RetestSequenceCase(
        String id,
        List<RetestTask> priorTrainingTasks,
        List<String> questionCompetencies,
        int expectedRequestedFocus,
        int expectedPrioritized,
        double expectedRetestCoverage
    ) {}

    private record RetestTask(String competency, int priority) {}
}
