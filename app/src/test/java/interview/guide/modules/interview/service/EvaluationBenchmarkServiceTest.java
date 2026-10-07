package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.EvaluationReport.EvaluationStatus;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.interview.model.EvaluationBenchmarkRequest;
import interview.guide.modules.interview.model.EvaluationBenchmarkRequest.BenchmarkCase;
import interview.guide.modules.interview.model.EvaluationBenchmarkResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@DisplayName("面试评分稳定性评测")
class EvaluationBenchmarkServiceTest {

    private final EvaluationBenchmarkService service = new EvaluationBenchmarkService(
        mock(UnifiedEvaluationService.class),
        mock(LlmProviderRegistry.class)
    );

    @Test
    @DisplayName("汇总覆盖率、分数波动、等级排序与期望区间命中率")
    void shouldSummarizeRepeatedEvaluationRuns() {
        EvaluationBenchmarkRequest request = request(List.of(
            benchmarkCase("weak", 0, 0, 39),
            benchmarkCase("medium", 1, 60, 74),
            benchmarkCase("strong", 2, 75, 100)
        ));
        List<EvaluationReport> reports = List.of(
            report(20, 65, 90, EvaluationStatus.SCORED),
            report(30, 70, 85, EvaluationStatus.SCORED),
            report(25, 68, 0, EvaluationStatus.EVALUATION_FAILED)
        );

        EvaluationBenchmarkResponse response = service.summarize(request, reports);

        assertThat(response.summary().totalCases()).isEqualTo(3);
        assertThat(response.summary().totalEvaluations()).isEqualTo(9);
        assertThat(response.summary().scoredEvaluations()).isEqualTo(8);
        assertThat(response.summary().evidenceSupportedEvaluations()).isEqualTo(8);
        assertThat(response.summary().evaluationCoverage())
            .isCloseTo(8.0 / 9.0, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(response.summary().evidenceCoverage()).isEqualTo(1.0);
        assertThat(response.summary().medianScoreRange()).isEqualTo(5.0);
        assertThat(response.summary().medianRubricRange()).isZero();
        assertThat(response.summary().rankingAccuracy()).isEqualTo(1.0);
        assertThat(response.summary().expectedBandHitRate()).isEqualTo(1.0);
        assertThat(response.cases().get(2).statuses())
            .containsExactly("SCORED", "SCORED", "EVALUATION_FAILED");
    }

    @Test
    @DisplayName("拒绝最低分高于最高分的错误样例")
    void shouldRejectInvalidExpectedScoreBand() {
        EvaluationBenchmarkRequest request = request(List.of(
            benchmarkCase("invalid", 0, 80, 20)
        ));

        assertThatThrownBy(() -> service.evaluate(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("期望分数区间无效");
    }

    private EvaluationBenchmarkRequest request(List<BenchmarkCase> cases) {
        return new EvaluationBenchmarkRequest("seed-v1", null, 3, "", cases);
    }

    private BenchmarkCase benchmarkCase(String id, int rank, int min, int max) {
        return new BenchmarkCase(
            id,
            "java-hashmap",
            rank,
            min,
            max,
            "HashMap 为什么线程不安全？",
            "Java",
            "固定回答"
        );
    }

    private EvaluationReport report(int weak, int medium, int strong,
                                    EvaluationStatus strongStatus) {
        List<EvaluationReport.QuestionEvaluation> details = List.of(
            question(0, weak, EvaluationStatus.SCORED),
            question(1, medium, EvaluationStatus.SCORED),
            question(2, strong, strongStatus)
        );
        int scored = (int) details.stream()
            .filter(item -> item.evaluationStatus() == EvaluationStatus.SCORED)
            .count();
        return new EvaluationReport(
            "benchmark",
            3,
            3,
            scored,
            3 - scored,
            scored,
            scored / 3.0,
            scored == 0 ? 0.0 : 1.0,
            0,
            List.of(),
            details,
            "",
            List.of(),
            List.of(),
            List.of(),
            List.of()
        );
    }

    private EvaluationReport.QuestionEvaluation question(
            int index, int score, EvaluationStatus status) {
        return new EvaluationReport.QuestionEvaluation(
            index,
            "问题" + index,
            "Java",
            "回答",
            score,
            "反馈",
            0,
            List.of("回答"),
            List.of(),
            List.of(),
            "继续补充",
            status
        );
    }
}
