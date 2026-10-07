package interview.guide.modules.interview.model;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 评分稳定性评测结果。覆盖率与命中率均使用 0—1 比例。
 */
public record EvaluationBenchmarkResponse(
    String datasetId,
    OffsetDateTime evaluatedAt,
    int repetitions,
    Summary summary,
    List<CaseResult> cases
) {
    public record Summary(
        int totalCases,
        int totalEvaluations,
        int scoredEvaluations,
        int evidenceSupportedEvaluations,
        double evaluationCoverage,
        double evidenceCoverage,
        double medianScoreRange,
        double medianRubricRange,
        double rankingAccuracy,
        double expectedBandHitRate
    ) {}

    public record CaseResult(
        String id,
        String groupId,
        int expectedRank,
        int expectedMinScore,
        int expectedMaxScore,
        List<Integer> scores,
        List<Integer> rubricLevels,
        List<String> statuses,
        List<List<String>> answerEvidence,
        Double meanScore,
        Integer minScore,
        Integer maxScore,
        Integer scoreRange,
        Integer rubricRange,
        boolean expectedBandHit
    ) {}
}
