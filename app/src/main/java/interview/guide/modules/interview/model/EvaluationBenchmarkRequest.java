package interview.guide.modules.interview.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 固定问答评分稳定性评测请求，仅在显式开启 benchmark 配置时可用。
 */
public record EvaluationBenchmarkRequest(
    @NotBlank(message = "数据集 ID 不能为空")
    String datasetId,
    String llmProvider,
    @Min(value = 1, message = "重复次数至少为1")
    @Max(value = 5, message = "重复次数最多为5")
    int repetitions,
    String referenceContext,
    @NotEmpty(message = "至少提供一条评测样例")
    @Size(max = 50, message = "单次最多评测50条样例")
    List<@Valid BenchmarkCase> cases
) {
    public record BenchmarkCase(
        @NotBlank(message = "样例 ID 不能为空")
        String id,
        @NotBlank(message = "分组 ID 不能为空")
        String groupId,
        @Min(value = 0, message = "期望等级不能小于0")
        @Max(value = 10, message = "期望等级不能大于10")
        int expectedRank,
        @Min(value = 0, message = "期望最低分不能小于0")
        @Max(value = 100, message = "期望最低分不能大于100")
        int expectedMinScore,
        @Min(value = 0, message = "期望最高分不能小于0")
        @Max(value = 100, message = "期望最高分不能大于100")
        int expectedMaxScore,
        @NotBlank(message = "问题不能为空")
        String question,
        @NotBlank(message = "类别不能为空")
        String category,
        @NotBlank(message = "回答不能为空")
        String answer
    ) {}
}
