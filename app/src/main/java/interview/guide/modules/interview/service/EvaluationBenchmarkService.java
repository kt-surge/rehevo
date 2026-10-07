package interview.guide.modules.interview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.EvaluationReport.EvaluationStatus;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.QuestionEvaluationGuide;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.EvaluationBenchmarkRequest;
import interview.guide.modules.interview.model.EvaluationBenchmarkRequest.BenchmarkCase;
import interview.guide.modules.interview.model.EvaluationBenchmarkResponse;
import interview.guide.modules.interview.model.EvaluationBenchmarkResponse.CaseResult;
import interview.guide.modules.interview.model.EvaluationBenchmarkResponse.Summary;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 使用固定问答重复执行统一评估，计算覆盖率、分数波动和等级排序准确率。
 */
@Service
@RequiredArgsConstructor
public class EvaluationBenchmarkService {

    private final UnifiedEvaluationService unifiedEvaluationService;
    private final LlmProviderRegistry llmProviderRegistry;

    public EvaluationBenchmarkResponse evaluate(EvaluationBenchmarkRequest request) {
        validateScoreBands(request.cases());
        ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(request.llmProvider(), LlmProviderRegistry.ToolAccess.NONE);
        List<QaRecord> qaRecords = toQaRecords(request.cases());
        List<EvaluationReport> reports = new ArrayList<>();

        for (int run = 0; run < request.repetitions(); run++) {
            String sessionId = "benchmark-" + UUID.randomUUID().toString().substring(0, 8);
            reports.add(unifiedEvaluationService.evaluate(
                chatClient,
                sessionId,
                qaRecords,
                "",
                request.referenceContext()
            ));
        }

        return summarize(request, reports);
    }

    EvaluationBenchmarkResponse summarize(EvaluationBenchmarkRequest request,
                                          List<EvaluationReport> reports) {
        List<CaseResult> caseResults = new ArrayList<>();
        int scoredEvaluations = 0;
        int evidenceSupportedEvaluations = 0;

        for (int index = 0; index < request.cases().size(); index++) {
            BenchmarkCase benchmarkCase = request.cases().get(index);
            List<Integer> scores = new ArrayList<>();
            List<Integer> rubricLevels = new ArrayList<>();
            List<String> statuses = new ArrayList<>();
            List<List<String>> answerEvidence = new ArrayList<>();

            for (EvaluationReport report : reports) {
                EvaluationReport.QuestionEvaluation evaluation = report.questionDetails().get(index);
                EvaluationStatus status = evaluation.evaluationStatus();
                statuses.add(status.name());
                if (status == EvaluationStatus.SCORED) {
                    scores.add(evaluation.score());
                    rubricLevels.add(evaluation.rubricLevel());
                    List<String> evidence = evaluation.answerEvidence() != null
                        ? evaluation.answerEvidence() : List.of();
                    answerEvidence.add(evidence);
                    if (!evidence.isEmpty()) evidenceSupportedEvaluations++;
                    scoredEvaluations++;
                }
            }

            Double mean = scores.isEmpty()
                ? null
                : scores.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            Integer min = scores.stream().min(Integer::compareTo).orElse(null);
            Integer max = scores.stream().max(Integer::compareTo).orElse(null);
            Integer range = min != null && max != null ? max - min : null;
            Integer minRubric = rubricLevels.stream().min(Integer::compareTo).orElse(null);
            Integer maxRubric = rubricLevels.stream().max(Integer::compareTo).orElse(null);
            Integer rubricRange = minRubric != null && maxRubric != null
                ? maxRubric - minRubric : null;
            boolean expectedBandHit = mean != null
                && mean >= benchmarkCase.expectedMinScore()
                && mean <= benchmarkCase.expectedMaxScore();

            caseResults.add(new CaseResult(
                benchmarkCase.id(),
                benchmarkCase.groupId(),
                benchmarkCase.expectedRank(),
                benchmarkCase.expectedMinScore(),
                benchmarkCase.expectedMaxScore(),
                List.copyOf(scores),
                List.copyOf(rubricLevels),
                List.copyOf(statuses),
                List.copyOf(answerEvidence),
                mean,
                min,
                max,
                range,
                rubricRange,
                expectedBandHit
            ));
        }

        int totalEvaluations = request.cases().size() * reports.size();
        double coverage = totalEvaluations == 0 ? 0.0 : (double) scoredEvaluations / totalEvaluations;
        double evidenceCoverage = scoredEvaluations == 0
            ? 0.0
            : (double) evidenceSupportedEvaluations / scoredEvaluations;
        double medianRange = median(caseResults.stream()
            .map(CaseResult::scoreRange)
            .filter(value -> value != null)
            .sorted()
            .toList());
        double medianRubricRange = median(caseResults.stream()
            .map(CaseResult::rubricRange)
            .filter(value -> value != null)
            .sorted()
            .toList());
        double expectedBandHitRate = caseResults.isEmpty()
            ? 0.0
            : (double) caseResults.stream().filter(CaseResult::expectedBandHit).count() / caseResults.size();

        Summary summary = new Summary(
            request.cases().size(),
            totalEvaluations,
            scoredEvaluations,
            evidenceSupportedEvaluations,
            coverage,
            evidenceCoverage,
            medianRange,
            medianRubricRange,
            rankingAccuracy(caseResults),
            expectedBandHitRate
        );
        return new EvaluationBenchmarkResponse(
            request.datasetId(),
            OffsetDateTime.now(),
            reports.size(),
            summary,
            List.copyOf(caseResults)
        );
    }

    private List<QaRecord> toQaRecords(List<BenchmarkCase> cases) {
        List<QaRecord> records = new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            BenchmarkCase item = cases.get(index);
            QuestionEvaluationGuide guide = QuestionEvaluationGuide.standard(
                item.category(), List.of(), "根据回答质量决定是否深挖原理或工程边界", "BENCHMARK_SEED");
            records.add(new QaRecord(
                index, item.question(), item.category(), item.answer(), guide));
        }
        return records;
    }

    private void validateScoreBands(List<BenchmarkCase> cases) {
        for (BenchmarkCase item : cases) {
            if (item.expectedMinScore() > item.expectedMaxScore()) {
                throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "样例 " + item.id() + " 的期望分数区间无效"
                );
            }
        }
    }

    private double median(List<Integer> sortedValues) {
        if (sortedValues.isEmpty()) return 0.0;
        int middle = sortedValues.size() / 2;
        if (sortedValues.size() % 2 == 1) return sortedValues.get(middle);
        return (sortedValues.get(middle - 1) + sortedValues.get(middle)) / 2.0;
    }

    private double rankingAccuracy(List<CaseResult> results) {
        Map<String, List<CaseResult>> groups = new HashMap<>();
        results.forEach(item -> groups.computeIfAbsent(item.groupId(), key -> new ArrayList<>()).add(item));

        int comparablePairs = 0;
        int correctPairs = 0;
        for (List<CaseResult> group : groups.values()) {
            List<CaseResult> ranked = group.stream()
                .sorted(Comparator.comparingInt(CaseResult::expectedRank))
                .toList();
            for (int i = 1; i < ranked.size(); i++) {
                comparablePairs++;
                Double previous = ranked.get(i - 1).meanScore();
                Double current = ranked.get(i).meanScore();
                if (previous != null && current != null && current > previous) {
                    correctPairs++;
                }
            }
        }
        return comparablePairs == 0 ? 0.0 : (double) correctPairs / comparablePairs;
    }
}
