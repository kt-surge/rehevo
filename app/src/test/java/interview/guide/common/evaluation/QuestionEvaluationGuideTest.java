package interview.guide.common.evaluation;

import interview.guide.common.evaluation.QuestionEvaluationGuide.RubricLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("题目评分依据")
class QuestionEvaluationGuideTest {

  @Test
  @DisplayName("补齐缺失的 Rubric 等级并清理重复关键点")
  void shouldNormalizeIncompleteRubricAndKeyPoints() {
    QuestionEvaluationGuide guide = new QuestionEvaluationGuide(
        "缓存一致性",
        List.of("更新数据库", "更新数据库", "  删除缓存  "),
        "追问失败补偿",
        "SKILL_REFERENCE",
        List.of(
            new RubricLevel(2, "能说出 Cache Aside"),
            new RubricLevel(4, "能覆盖失败补偿与验证")
        )
    );

    assertThat(guide.keyPoints()).containsExactly("更新数据库", "删除缓存");
    assertThat(guide.rubric())
        .extracting(RubricLevel::level)
        .containsExactly(0, 1, 2, 3, 4);
    assertThat(guide.rubric().get(2).criteria()).isEqualTo("能说出 Cache Aside");
  }

  @Test
  @DisplayName("标准评分依据始终包含完整的五个等级")
  void shouldCreateCompleteStandardGuide() {
    QuestionEvaluationGuide guide = QuestionEvaluationGuide.standard(
        "事务边界", List.of("代理调用"), "追问自调用", "FALLBACK");

    assertThat(guide.rubric()).hasSize(5);
    assertThat(guide.rubric().getFirst().level()).isZero();
    assertThat(guide.rubric().getLast().level()).isEqualTo(4);
  }
}
