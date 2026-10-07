package interview.guide.modules.interview.service;

import interview.guide.common.evaluation.QuestionEvaluationGuide;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewReportDTO.TrainingTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("训练目标关联由来源定义而非生成显示名决定")
class TrainingTargetAssociationRegressionTest {
  private final InterviewPlanService service = new InterviewPlanService();
  private final TrainingTask task = new TrainingTask("事务消息边界", List.of(0), "原回答混淆提交与 ACK",
      "练习提交后 ACK 前崩溃时的幂等恢复", "说明事务与消费确认各自边界", 5);
  private final String targetId = TrainingTargetSelector.select(List.of(task)).getFirst().id();

  @Test
  @DisplayName("模型改变能力点显示名仍关联本次明确的训练目标")
  void renamedCompetencyKeepsExplicitTarget() {
    var plan = service.build("java-backend", "mid",
        List.of(question(0, "Redis 消费故障分析", false, List.of(targetId))), List.of(task));
    assertThat(plan.prioritizedCompetencies()).isEqualTo(1);
    assertThat(plan.retestCoverage()).isEqualTo(1);
    assertThat(plan.competencies().getFirst().priority()).isEqualTo(5);
  }

  @Test
  @DisplayName("多个主问题关联同一训练目标时只计一次安排覆盖")
  void countsDistinctTargetsAcrossDifferentLabels() {
    var plan = service.build("java-backend", "mid", List.of(
        question(0, "ACK 顺序", false, List.of(targetId)),
        question(1, "重复投递恢复", false, List.of(targetId))), List.of(task));
    assertThat(plan.prioritizedCompetencies()).isEqualTo(1);
    assertThat(plan.retestCoverage()).isEqualTo(1);
  }

  @Test
  @DisplayName("未知目标不能通过同名能力点兜底冒领安排覆盖")
  void unknownTargetDoesNotFallBackToLabel() {
    var plan = service.build("java-backend", "mid",
        List.of(question(0, task.competency(), false, List.of("training:unknown"))), List.of(task));
    assertThat(plan.prioritizedCompetencies()).isZero();
    assertThat(plan.retestCoverage()).isZero();
  }

  @Test
  @DisplayName("新题显式没有关联时不能通过同名能力点计入覆盖")
  void declaredEmptyTargetDoesNotFallBackToLabel() {
    var plan = service.build("java-backend", "mid",
        List.of(question(0, task.competency(), false, List.of())), List.of(task));
    assertThat(plan.prioritizedCompetencies()).isZero();
    assertThat(plan.trainingTargets().getFirst().questionIndexes()).isEmpty();
  }

  @Test
  @DisplayName("单题声明多个目标时拒绝关联而非重复增加覆盖")
  void multipleTargetsAreNotAccepted() {
    var other = new TrainingTask("事务代理", List.of(1), "待复核", "解释自调用", "指出代理边界", 4);
    String otherId = TrainingTargetSelector.select(List.of(other)).getFirst().id();
    var plan = service.build("java-backend", "mid",
        List.of(question(0, task.competency(), false, List.of(targetId, otherId))), List.of(task, other));
    assertThat(plan.requestedFocusCompetencies()).isEqualTo(2);
    assertThat(plan.prioritizedCompetencies()).isZero();
  }

  @Test
  @DisplayName("计划保留训练动作与验收标准及关联主问题索引")
  void persistsTargetDefinitionAndQuestionAssignment() {
    var plan = service.build("java-backend", "mid",
        List.of(question(7, task.competency(), false, List.of(targetId))), List.of(task));
    assertThat(plan.trainingTargets()).singleElement().satisfies(target -> {
      assertThat(target.targetId()).isEqualTo(targetId);
      assertThat(target.action()).isEqualTo(task.action());
      assertThat(target.completionCriteria()).isEqualTo(task.completionCriteria());
      assertThat(target.questionIndexes()).containsExactly(7);
    });
  }

  @Test
  @DisplayName("仅追问关联不能把主问题复测记为已安排")
  void followUpOnlyDoesNotCoverTarget() {
    var plan = service.build("java-backend", "mid",
        List.of(question(1, "故障边界", true, List.of(targetId))), List.of(task));
    assertThat(plan.prioritizedCompetencies()).isZero();
  }

  private InterviewQuestionDTO question(int index, String label, boolean followUp, List<String> ids) {
    var guide = new QuestionEvaluationGuide(label, List.of("提交与ACK顺序"), "故障边界",
        "CONTROLLED", List.of(), ids);
    return InterviewQuestionDTO.create(index, "提交后ACK前进程退出会怎样恢复？", "TECH",
        "消息一致性", "", followUp, followUp ? 0 : null, guide);
  }
}
