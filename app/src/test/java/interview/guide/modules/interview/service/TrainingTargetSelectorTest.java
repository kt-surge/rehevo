package interview.guide.modules.interview.service;

import interview.guide.modules.interview.model.InterviewReportDTO.TrainingTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("训练目标的定义标识与选择边界")
class TrainingTargetSelectorTest {
  @Test
  @DisplayName("原因优先级和旧题号变化不改变目标定义标识")
  void metadataDoesNotChangeDefinition() {
    var original = task("事务", "分析提交与确认", "指出崩溃窗口", 5);
    var later = new TrainingTask(" 事务 ", List.of(9), "另一场待复核原因",
        original.action(), original.completionCriteria(), 1);
    assertThat(id(original)).isEqualTo(id(later));
    assertThat(id(task("事务", "分析回滚", original.completionCriteria(), 5))).isNotEqualTo(id(original));
    assertThat(id(task("事务", original.action(), "覆盖重复投递", 5))).isNotEqualTo(id(original));
  }

  @Test
  @DisplayName("忽略空任务同名取高优先级且最多选择三个")
  void filtersAndCapsPriorityTargets() {
    var tasks = Arrays.asList(null, task(" ", "", "", 9),
        task("Java", "旧动作", "旧标准", 1), task(" java ", "新动作", "新标准", 5),
        task("Redis", "消息恢复", "幂等", 4), task("Spring", "代理", "自调用", 3),
        task("SQL", "索引", "执行计划", 2));
    assertThat(TrainingTargetSelector.select(tasks)).extracting(target -> target.task().priority())
        .containsExactly(5, 4, 3);
    assertThat(TrainingTargetSelector.select(null)).isEmpty();
  }

  private String id(TrainingTask task) {
    return TrainingTargetSelector.select(List.of(task)).getFirst().id();
  }

  private TrainingTask task(String competency, String action, String criteria, int priority) {
    return new TrainingTask(competency, List.of(0), "待复核", action, criteria, priority);
  }
}
