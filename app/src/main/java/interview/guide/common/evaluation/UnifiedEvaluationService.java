package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.AiCallCancellation;
import interview.guide.common.evaluation.EvaluationReport.CategoryScore;
import interview.guide.common.evaluation.EvaluationReport.EvaluationStatus;
import interview.guide.common.evaluation.EvaluationReport.QuestionEvaluation;
import interview.guide.common.evaluation.EvaluationReport.ReferenceAnswer;
import interview.guide.common.evaluation.EvaluationReport.TrainingTask;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * 统一面试评估服务
 * 文字面试和语音面试共用的评估逻辑：分批评估 + 结构化输出 + 二次汇总 + 降级兜底
 */
@Service
public class UnifiedEvaluationService {

    private static final Logger log = LoggerFactory.getLogger(UnifiedEvaluationService.class);
    private static final int MAX_REFERENCE_CONTEXT_CHARS = 6000;

    private final PromptTemplate systemPromptTemplate;
    private final PromptTemplate userPromptTemplate;
    private final BeanOutputConverter<BatchReportDTO> outputConverter;
    private final PromptTemplate summarySystemPromptTemplate;
    private final PromptTemplate summaryUserPromptTemplate;
    private final BeanOutputConverter<SummaryDTO> summaryOutputConverter;
    private final StructuredOutputInvoker structuredOutputInvoker;
    private final int evaluationBatchSize;
    private final int maxConcurrentBatches;
    private final int failedItemRetryLimit;
    private final int requestDeadlineSeconds;
    private final ResourceLoader resourceLoader;
    private final ApplicationMetrics applicationMetrics;
    private final Executor evaluationExecutor;

    // 批次评估结果
    record BatchReportDTO(
        int overallScore,
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<QuestionEvalDTO> questionEvaluations
    ) {}

    record QuestionEvalDTO(
        int questionIndex,
        int score,
        String feedback,
        Integer rubricLevel,
        List<String> answerEvidence,
        List<String> missingPoints,
        List<String> factualRisks,
        String nextAction,
        String referenceAnswer,
        List<String> keyPoints
    ) {}

    record ResolvedQuestionEvaluation(
        QuestionEvalDTO evaluation,
        EvaluationStatus status
    ) {}

    private static final class CategoryAccumulator {
        private int questionCount;
        private int answeredCount;
        private final List<Integer> scores = new ArrayList<>();
    }

    private record BatchResult(
        int startIndex,
        int endIndex,
        BatchReportDTO report
    ) {}

    private record BatchRequest(
        int startIndex,
        int endIndex,
        List<QaRecord> batch
    ) {}

    private record BatchFuture(BatchExecution execution, Future<BatchResult> future) {}

    /**
     * 允许等待线程和迟到的工作线程竞争记录终态，但同一批次只会写入一次指标。
     */
    private static final class BatchExecution {
        private final BatchRequest request;
        private final AtomicBoolean metricRecorded = new AtomicBoolean();

        private BatchExecution(BatchRequest request) {
            this.request = request;
        }

        private void recordMetric(ApplicationMetrics metrics, long elapsedNanos,
                                  ApplicationMetrics.Outcome outcome) {
            if (metricRecorded.compareAndSet(false, true)) {
                metrics.recordInterviewEvaluationBatch(elapsedNanos, outcome);
            }
        }
    }

    private record EvaluationDeadline(long deadlineNanos) {
        static EvaluationDeadline afterSeconds(int seconds) {
            long durationNanos = Duration.ofSeconds(Math.max(0, seconds)).toNanos();
            return new EvaluationDeadline(System.nanoTime() + durationNanos);
        }

        boolean isExpired() {
            return System.nanoTime() >= deadlineNanos;
        }

        boolean canContinue() {
            return !isExpired() && !Thread.currentThread().isInterrupted();
        }

        long remainingMillis() {
            return Math.max(0, TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
        }
    }

    record TrainingTaskDTO(
        String competency,
        List<Integer> questionIndexes,
        String reason,
        String action,
        String completionCriteria,
        int priority
    ) {}

    record SummaryDTO(
        String overallFeedback,
        List<String> strengths,
        List<String> improvements,
        List<TrainingTaskDTO> trainingTasks
    ) {}

    public UnifiedEvaluationService(
            StructuredOutputInvoker structuredOutputInvoker,
            ResourceLoader resourceLoader,
            InterviewEvaluationProperties evaluationProperties,
            ApplicationMetrics applicationMetrics,
            @Qualifier("interviewEvaluationExecutor") Executor evaluationExecutor) throws IOException {
        this.structuredOutputInvoker = structuredOutputInvoker;
        this.resourceLoader = resourceLoader;
        this.systemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSystemPromptPath()));
        this.userPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getUserPromptPath()));
        this.outputConverter = new BeanOutputConverter<>(BatchReportDTO.class);
        this.summarySystemPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummarySystemPromptPath()));
        this.summaryUserPromptTemplate = new PromptTemplate(loadPrompt(evaluationProperties.getSummaryUserPromptPath()));
        this.summaryOutputConverter = new BeanOutputConverter<>(SummaryDTO.class);
        this.evaluationBatchSize = Math.max(1, evaluationProperties.getBatchSize());
        this.maxConcurrentBatches = Math.max(1, evaluationProperties.getMaxConcurrentBatches());
        this.failedItemRetryLimit = Math.max(0, evaluationProperties.getFailedItemRetryLimit());
        this.requestDeadlineSeconds = Math.max(0, evaluationProperties.getRequestDeadlineSeconds());
        this.applicationMetrics = applicationMetrics;
        this.evaluationExecutor = evaluationExecutor;
    }

    /**
     * 评估面试问答（文字和语音通用）
     *
     * @param chatClient  LLM 客户端
     * @param sessionId   会话ID（用于日志）
     * @param qaRecords   问答记录列表
     * @param resumeText  简历摘要（可选，可为 null）
     * @return 评估报告
     */
    public EvaluationReport evaluate(ChatClient chatClient,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText) {
        return evaluate(chatClient, sessionId, qaRecords, resumeText, null);
    }

    public EvaluationReport evaluate(ChatClient chatClient,
                                     String sessionId,
                                     List<QaRecord> qaRecords,
                                     String resumeText,
                                     String referenceContext) {
        log.info("开始评估面试: sessionId={}, 共{}题", sessionId, qaRecords.size());

        String resumeContext = resumeText != null ? resumeText : "";
        // 超长简历截断，保留前 3000 字符（约 1500~2000 tokens），避免极端情况下 token 消耗过大
        if (resumeContext.length() > 3000) {
            resumeContext = resumeContext.substring(0, 3000) + "\n...(简历内容过长，已截断)";
        }
        String referenceBaseline = referenceContext != null ? referenceContext.trim() : "";
        if (referenceBaseline.length() > MAX_REFERENCE_CONTEXT_CHARS) {
            referenceBaseline = referenceBaseline.substring(0, MAX_REFERENCE_CONTEXT_CHARS)
                + "\n...(参考基线过长，已截断)";
        }
        EvaluationDeadline deadline = EvaluationDeadline.afterSeconds(requestDeadlineSeconds);

        // 分批评估
        List<BatchResult> batchResults = evaluateInBatches(
            chatClient, sessionId, resumeContext, qaRecords, referenceBaseline, deadline
        );

        // 合并批次结果
        List<ResolvedQuestionEvaluation> mergedEvaluations = mergeQuestionEvaluations(batchResults, qaRecords);
        mergedEvaluations = retryFailedItems(
            chatClient,
            sessionId,
            resumeContext,
            referenceBaseline,
            qaRecords,
            mergedEvaluations,
            deadline
        );
        String fallbackFeedback = buildGroundedFeedback(qaRecords, mergedEvaluations);
        List<String> fallbackStrengths = List.of();
        List<String> fallbackImprovements = mergedEvaluations.stream()
            .filter(item -> item.status() == EvaluationStatus.SCORED)
            .flatMap(item -> sanitizeItems(item.evaluation().missingPoints()).stream())
            .distinct().limit(8).toList();

        // 二次汇总
        SummaryDTO summary = summarizeBatchResults(
            chatClient, sessionId, resumeContext, referenceBaseline, qaRecords,
            mergedEvaluations, fallbackFeedback, fallbackStrengths, fallbackImprovements, deadline
        );

        EvaluationReport report = buildReport(sessionId, qaRecords, mergedEvaluations,
            summary.overallFeedback(), summary.strengths(), summary.improvements(),
            summary.trainingTasks());
        applicationMetrics.recordInterviewEvaluation(
            report.answeredQuestions(), report.scoredQuestions(), report.failedQuestions(),
            report.evidenceSupportedQuestions(), report.evaluationCoverage(),
            report.evidenceCoverage());
        return report;
    }

    private String loadPrompt(String path) throws IOException {
        Resource resource = resourceLoader.getResource(path);
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    private List<BatchResult> evaluateInBatches(ChatClient chatClient, String sessionId,
                                                 String resumeContext, List<QaRecord> qaRecords,
                                                 String referenceContext, EvaluationDeadline deadline) {
        List<BatchResult> results = new ArrayList<>();
        List<BatchRequest> pending = new ArrayList<>();
        for (int start = 0; start < qaRecords.size(); start += evaluationBatchSize) {
            int end = Math.min(start + evaluationBatchSize, qaRecords.size());
            List<QaRecord> batch = qaRecords.subList(start, end);
            if (!deadline.canContinue()) {
                log.warn("评估预算已耗尽，跳过剩余批次: sessionId={}, startIndex={}, endIndex={}",
                    sessionId, start, end);
                results.add(new BatchResult(start, end, null));
                applicationMetrics.recordInterviewEvaluationBatch(0, ApplicationMetrics.Outcome.SKIPPED);
                continue;
            }
            pending.add(new BatchRequest(start, end, List.copyOf(batch)));
        }
        for (int start = 0; start < pending.size(); start += maxConcurrentBatches) {
            int end = Math.min(start + maxConcurrentBatches, pending.size());
            List<BatchFuture> futures = new ArrayList<>();
            for (BatchRequest request : pending.subList(start, end)) {
                if (!deadline.canContinue()) {
                    results.add(new BatchResult(request.startIndex(), request.endIndex(), null));
                    applicationMetrics.recordInterviewEvaluationBatch(0, ApplicationMetrics.Outcome.SKIPPED);
                    continue;
                }
                try {
                    BatchExecution execution = new BatchExecution(request);
                    FutureTask<BatchResult> future = cancellableTask(
                        () -> evaluateBatchResult(chatClient, sessionId, resumeContext, referenceContext,
                            execution, deadline));
                    evaluationExecutor.execute(future);
                    futures.add(new BatchFuture(execution, future));
                } catch (RejectedExecutionException e) {
                    log.warn("评估批次执行器已满，当前批次明确失败: sessionId={}, startIndex={}, endIndex={}",
                        sessionId, request.startIndex(), request.endIndex());
                    results.add(new BatchResult(request.startIndex(), request.endIndex(), null));
                    applicationMetrics.recordInterviewEvaluationBatch(0, ApplicationMetrics.Outcome.FAILURE);
                }
            }
            for (BatchFuture future : futures) {
                results.add(awaitBatchResult(future, deadline, sessionId));
            }
        }
        results.sort(Comparator.comparingInt(BatchResult::startIndex));
        return results;
    }

    private BatchResult evaluateBatchResult(ChatClient chatClient, String sessionId,
                                             String resumeContext, String referenceContext,
                                             BatchExecution execution, EvaluationDeadline deadline) {
        long startedAt = System.nanoTime();
        BatchReportDTO report = evaluateBatch(
            chatClient, sessionId, resumeContext, referenceContext, execution.request.batch(), deadline);
        long elapsed = System.nanoTime() - startedAt;
        execution.recordMetric(applicationMetrics,
            elapsed, report == null ? ApplicationMetrics.Outcome.FAILURE : ApplicationMetrics.Outcome.SUCCESS);
        return new BatchResult(execution.request.startIndex(), execution.request.endIndex(), report);
    }

    private BatchResult awaitBatchResult(BatchFuture batchFuture,
                                          EvaluationDeadline deadline, String sessionId) {
        try {
            long remainingMillis = deadline.remainingMillis();
            if (remainingMillis <= 0 || !deadline.canContinue()) {
                batchFuture.future().cancel(true);
                batchFuture.execution().recordMetric(
                    applicationMetrics, 0, ApplicationMetrics.Outcome.FAILURE);
                return new BatchResult(batchFuture.execution().request.startIndex(),
                    batchFuture.execution().request.endIndex(), null);
            }
            return batchFuture.future().get(remainingMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            batchFuture.future().cancel(true);
            log.warn("评估批次超过整份报告预算，取消等待: sessionId={}", sessionId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            batchFuture.future().cancel(true);
            log.warn("评估批次等待被中断: sessionId={}", sessionId);
        } catch (ExecutionException e) {
            log.error("评估批次执行异常: sessionId={}", sessionId, e.getCause());
        } catch (CancellationException e) {
            log.warn("评估批次已取消: sessionId={}", sessionId);
        }
        batchFuture.execution().recordMetric(applicationMetrics, 0, ApplicationMetrics.Outcome.FAILURE);
        return new BatchResult(batchFuture.execution().request.startIndex(),
            batchFuture.execution().request.endIndex(), null);
    }

    private BatchReportDTO evaluateBatch(ChatClient chatClient, String sessionId,
                                          String resumeContext, String referenceContext,
                                          List<QaRecord> batch, EvaluationDeadline deadline) {
        String qaRecords = buildQARecords(batch);
        String systemPrompt = systemPromptTemplate.render();

        Map<String, Object> variables = new HashMap<>();
        variables.put("resumeText", resumeContext);
        variables.put("qaRecords", qaRecords);
        variables.put("referenceContext",
            (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
        String userPrompt = userPromptTemplate.render(variables);

        String systemPromptWithFormat = systemPrompt + "\n\n" + outputConverter.getFormat();
        try {
            return structuredOutputInvoker.invoke(
                chatClient, systemPromptWithFormat, userPrompt, outputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "批次评估失败：", "批次评估", log,
                deadline::canContinue
            );
        } catch (Exception e) {
            log.error("批次评估失败: sessionId={}, batchSize={}, error={}",
                sessionId, batch.size(), e.getMessage(), e);
            // 返回空报告，让合并逻辑用零分兜底
            return null;
        }
    }

    private String buildQARecords(List<QaRecord> batch) {
        StringBuilder sb = new StringBuilder();
        for (QaRecord q : batch) {
            sb.append(String.format("questionIndex=%d（显示第%d题）[%s]: %s\n",
                q.questionIndex(), q.questionIndex() + 1, q.category(), q.question()));
            sb.append(String.format("回答: %s\n\n",
                q.userAnswer() != null ? q.userAnswer() : "(未回答)"));
            appendEvaluationGuide(sb, q.evaluationGuide());
        }
        return sb.toString();
    }

    private void appendEvaluationGuide(StringBuilder sb, QuestionEvaluationGuide guide) {
        if (guide == null) return;
        sb.append("本题评分依据:\n");
        sb.append("- 能力点: ").append(orEmpty(guide.competency())).append('\n');
        sb.append("- 关键点: ").append(String.join("；", guide.keyPoints())).append('\n');
        sb.append("- 追问方向: ").append(orEmpty(guide.followUpDirection())).append('\n');
        sb.append("- 来源: ").append(orEmpty(guide.source())).append('\n');
        for (QuestionEvaluationGuide.RubricLevel level : guide.rubric()) {
            sb.append("- Level ").append(level.level()).append(": ")
                .append(level.criteria()).append('\n');
        }
        sb.append('\n');
    }

    private String orEmpty(String value) {
        return value != null ? value : "";
    }

    private List<ResolvedQuestionEvaluation> mergeQuestionEvaluations(
            List<BatchResult> batchResults, List<QaRecord> qaRecords) {
        List<ResolvedQuestionEvaluation> merged = new ArrayList<>();
        for (BatchResult result : batchResults) {
            int expectedSize = result.endIndex() - result.startIndex();
            List<QuestionEvalDTO> current =
                result.report() != null && result.report().questionEvaluations() != null
                    ? result.report().questionEvaluations()
                    : List.of();
            for (int i = 0; i < expectedSize; i++) {
                QaRecord question = qaRecords.get(result.startIndex() + i);
                merged.add(resolveEvaluation(question, current));
            }
        }
        return merged;
    }

    private List<ResolvedQuestionEvaluation> retryFailedItems(
            ChatClient chatClient,
            String sessionId,
            String resumeContext,
            String referenceContext,
            List<QaRecord> qaRecords,
            List<ResolvedQuestionEvaluation> evaluations,
            EvaluationDeadline deadline) {
        if (failedItemRetryLimit == 0) return evaluations;

        List<ResolvedQuestionEvaluation> recovered = new ArrayList<>(evaluations);
        int retryCount = 0;
        for (int index = 0; index < recovered.size() && index < qaRecords.size(); index++) {
            if (!deadline.canContinue()) {
                log.warn("评估预算已耗尽，跳过失败题重试: sessionId={}", sessionId);
                break;
            }
            QaRecord question = qaRecords.get(index);
            ResolvedQuestionEvaluation current = recovered.get(index);
            boolean hasAnswer = question.userAnswer() != null && !question.userAnswer().isBlank();
            if (!hasAnswer || current.status() != EvaluationStatus.EVALUATION_FAILED) continue;
            if (retryCount >= failedItemRetryLimit) break;
            retryCount++;

            BatchReportDTO retryReport = executeWithinDeadline(() -> evaluateBatch(
                chatClient,
                sessionId,
                resumeContext,
                referenceContext,
                List.of(question),
                deadline
            ), deadline, sessionId, "失败题重试");
            ResolvedQuestionEvaluation retried = resolveEvaluation(question,
                retryReport != null && retryReport.questionEvaluations() != null
                    ? retryReport.questionEvaluations() : List.of());
            if (retried.status() == EvaluationStatus.SCORED) {
                recovered.set(index, retried);
                applicationMetrics.recordInterviewEvaluationItemRetry(
                    ApplicationMetrics.Outcome.RECOVERED);
            } else {
                applicationMetrics.recordInterviewEvaluationItemRetry(
                    ApplicationMetrics.Outcome.FAILURE);
            }
        }
        return recovered;
    }

    private static ResolvedQuestionEvaluation resolveEvaluation(
            QaRecord question, List<QuestionEvalDTO> evaluations) {
        if (question.userAnswer() == null || question.userAnswer().isBlank()) {
            return new ResolvedQuestionEvaluation(null, EvaluationStatus.UNANSWERED);
        }
        List<QuestionEvalDTO> matches = evaluations.stream()
            .filter(item -> item != null && item.questionIndex() == question.questionIndex()).toList();
        if (matches.size() != 1
                || sanitizeEvidence(matches.getFirst().answerEvidence(), question.userAnswer()).isEmpty()) {
            return new ResolvedQuestionEvaluation(null, EvaluationStatus.EVALUATION_FAILED);
        }
        QuestionEvalDTO source = matches.getFirst();
        QuestionEvalDTO grounded = new QuestionEvalDTO(source.questionIndex(), source.score(),
            source.feedback(), source.rubricLevel(), sanitizeEvidence(source.answerEvidence(), question.userAnswer()),
            source.missingPoints(), source.factualRisks(), source.nextAction(), source.referenceAnswer(), source.keyPoints());
        return new ResolvedQuestionEvaluation(grounded, EvaluationStatus.SCORED);
    }

    private static String buildGroundedFeedback(
            List<QaRecord> qaRecords, List<ResolvedQuestionEvaluation> evaluations) {
        List<String> feedback = new ArrayList<>();
        for (int i = 0; i < qaRecords.size() && i < evaluations.size(); i++) {
            ResolvedQuestionEvaluation resolved = evaluations.get(i);
            if (resolved.status() == EvaluationStatus.SCORED) {
                feedback.add("第" + (qaRecords.get(i).questionIndex() + 1) + "题："
                    + firstNonBlank(resolved.evaluation().feedback(), "已完成有原文证据的评分"));
            }
        }
        return feedback.isEmpty()
            ? "未获得有效的逐题评分，请重试评估；本次不能据此判断能力缺口。"
            : String.join("\n", feedback);
    }

    private SummaryDTO summarizeBatchResults(
            ChatClient chatClient, String sessionId, String resumeContext, String referenceContext,
            List<QaRecord> qaRecords, List<ResolvedQuestionEvaluation> evaluations,
            String fallbackFeedback, List<String> fallbackStrengths, List<String> fallbackImprovements,
            EvaluationDeadline deadline) {
        if (!deadline.canContinue() || evaluations.stream()
                .noneMatch(item -> item.status() == EvaluationStatus.SCORED)) {
            log.warn("评估预算已耗尽或没有可信逐题评分，跳过二次汇总: sessionId={}", sessionId);
            return new SummaryDTO(
                fallbackFeedback,
                fallbackStrengths,
                fallbackImprovements,
                buildFallbackTrainingTasks(qaRecords, evaluations)
            );
        }
        try {
            String summarySystem = summarySystemPromptTemplate.render();
            Map<String, Object> vars = new HashMap<>();
            vars.put("resumeText", resumeContext);
            vars.put("referenceContext",
                (referenceContext != null && !referenceContext.isBlank()) ? referenceContext : "无");
            vars.put("categorySummary", buildCategorySummary(qaRecords, evaluations));
            vars.put("questionHighlights", buildQuestionHighlights(qaRecords, evaluations));
            vars.put("fallbackOverallFeedback", fallbackFeedback);
            vars.put("fallbackStrengths", String.join("\n", fallbackStrengths));
            vars.put("fallbackImprovements", String.join("\n", fallbackImprovements));
            String summaryUser = summaryUserPromptTemplate.render(vars);

            String systemWithFormat = summarySystem + "\n\n" + summaryOutputConverter.getFormat();
            SummaryDTO dto = executeWithinDeadline(() -> structuredOutputInvoker.invoke(
                chatClient, systemWithFormat, summaryUser, summaryOutputConverter,
                ErrorCode.INTERVIEW_EVALUATION_FAILED, "总结评估失败：", "总结评估", log,
                deadline::canContinue
            ), deadline, sessionId, "总结评估");

            String feedback = dto != null && dto.overallFeedback() != null && !dto.overallFeedback().isBlank()
                ? dto.overallFeedback() : fallbackFeedback;
            List<String> strengths = sanitizeItems(dto != null ? dto.strengths() : null, fallbackStrengths);
            List<String> improvements = sanitizeItems(dto != null ? dto.improvements() : null, fallbackImprovements);
            List<TrainingTaskDTO> fallbackTasks = buildFallbackTrainingTasks(qaRecords, evaluations);
            List<TrainingTaskDTO> trainingTasks = sanitizeTrainingTasks(
                dto != null ? dto.trainingTasks() : null,
                fallbackTasks,
                qaRecords,
                evaluations
            );
            return new SummaryDTO(feedback, strengths, improvements, trainingTasks);
        } catch (Exception e) {
            log.warn("二次汇总评估失败，降级到有效逐题结果: sessionId={}, error={}", sessionId, e.getMessage(), e);
            return new SummaryDTO(
                fallbackFeedback,
                fallbackStrengths,
                fallbackImprovements,
                buildFallbackTrainingTasks(qaRecords, evaluations)
            );
        }
    }

  /** 与批次共用剩余预算；超时后取消实际线程，不采用迟到的模型结果。 */
  private <T> T executeWithinDeadline(Callable<T> operation, EvaluationDeadline deadline,
                                      String sessionId, String phase) {
    if (!deadline.canContinue()) return null;
    FutureTask<T> future = cancellableTask(operation);
    try {
      evaluationExecutor.execute(future);
      long remainingMillis = deadline.remainingMillis();
      if (remainingMillis <= 0 || !deadline.canContinue()) {
        future.cancel(true);
        return null;
      }
      return future.get(remainingMillis, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      log.warn("{}超过整份报告预算，取消执行: sessionId={}", phase, sessionId);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      log.warn("{}等待被中断: sessionId={}", phase, sessionId);
    } catch (RejectedExecutionException e) {
      log.warn("{}执行器已满，使用有效逐题结果降级: sessionId={}", phase, sessionId);
    } catch (ExecutionException e) {
      log.warn("{}执行失败，使用有效逐题结果降级: sessionId={}", phase, sessionId, e.getCause());
    } catch (CancellationException e) {
      log.warn("{}已取消: sessionId={}", phase, sessionId);
    }
    return null;
  }

  private <T> FutureTask<T> cancellableTask(Callable<T> operation) {
    AiCallCancellation cancellation = new AiCallCancellation();
    return new FutureTask<>(() -> {
      try (var binding = cancellation.bind()) {
        return operation.call();
      }
    }) {
      @Override
      protected void done() {
        if (isCancelled()) cancellation.cancel();
      }
    };
  }

    private List<TrainingTaskDTO> sanitizeTrainingTasks(
            List<TrainingTaskDTO> primary,
            List<TrainingTaskDTO> fallback,
            List<QaRecord> qaRecords,
            List<ResolvedQuestionEvaluation> evaluations) {
        List<TrainingTaskDTO> source = primary != null && !primary.isEmpty() ? primary : fallback;
        Map<Integer, QaRecord> eligibleQuestions = new HashMap<>();
        for (int i = 0; i < qaRecords.size() && i < evaluations.size(); i++) {
            QaRecord question = qaRecords.get(i);
            ResolvedQuestionEvaluation resolved = evaluations.get(i);
            if (resolveEvaluation(question, resolved.evaluation() == null
                    ? List.of() : List.of(resolved.evaluation())).status() == EvaluationStatus.SCORED) {
                eligibleQuestions.put(question.questionIndex(), question);
            }
        }
        List<TrainingTaskDTO> sanitized = source.stream()
            .filter(item -> item != null)
            .filter(item -> item.competency() != null && !item.competency().isBlank())
            .filter(item -> item.action() != null && !item.action().isBlank())
            .filter(item -> item.completionCriteria() != null && !item.completionCriteria().isBlank())
            .filter(item -> item.questionIndexes() != null && !item.questionIndexes().isEmpty())
            .filter(item -> item.questionIndexes().stream().allMatch(eligibleQuestions::containsKey))
            .filter(item -> item.questionIndexes().stream().map(eligibleQuestions::get)
                .filter(question -> question.evaluationGuide() != null)
                .allMatch(question -> item.competency().trim().equals(
                    question.evaluationGuide().competency())))
            .map(item -> new TrainingTaskDTO(
                item.competency().trim(),
                item.questionIndexes() == null ? List.of() : item.questionIndexes().stream()
                    .distinct()
                    .toList(),
                item.reason() != null ? item.reason().trim() : "",
                item.action().trim(),
                item.completionCriteria().trim(),
                Math.max(1, Math.min(5, item.priority()))
            ))
            .filter(item -> !item.questionIndexes().isEmpty())
            .toList();
        if (sanitized.isEmpty()) return fallback;
        List<TrainingTaskDTO> normalized = mergeTrainingTasksByCompetency(sanitized);
        if (primary != null && !primary.isEmpty()) {
            applicationMetrics.recordInterviewModelTrainingTaskNormalization(sanitized.size(), normalized.size());
        }
        return normalized;
    }

    /**
     * 二次汇总由模型生成，不能只依赖 prompt 约束来保证同能力点不会重复。
     * 这里保留最高优先级任务的行动与完成标准，并合并关联题号和原因，确保下一场
     * PREP 看到的是一个可追踪的复测重点而不是同义任务的随机副本。
     */
    private static List<TrainingTaskDTO> mergeTrainingTasksByCompetency(List<TrainingTaskDTO> tasks) {
        Map<String, List<TrainingTaskDTO>> grouped = tasks.stream()
            .collect(Collectors.groupingBy(
                item -> item.competency().trim().toLowerCase(java.util.Locale.ROOT),
                java.util.LinkedHashMap::new,
                Collectors.toList()
            ));
        return grouped.values().stream()
            .map(UnifiedEvaluationService::mergeTrainingTaskGroup)
            .sorted(java.util.Comparator.comparingInt(TrainingTaskDTO::priority).reversed())
            .limit(5)
            .toList();
    }

    private static TrainingTaskDTO mergeTrainingTaskGroup(List<TrainingTaskDTO> tasks) {
        TrainingTaskDTO primary = tasks.stream()
            .max(java.util.Comparator.comparingInt(TrainingTaskDTO::priority))
            .orElseThrow();
        List<Integer> questionIndexes = tasks.stream()
            .flatMap(item -> item.questionIndexes().stream())
            .distinct()
            .toList();
        List<String> reasons = tasks.stream()
            .map(TrainingTaskDTO::reason)
            .filter(reason -> reason != null && !reason.isBlank())
            .map(String::trim)
            .distinct()
            .limit(8)
            .toList();
        return new TrainingTaskDTO(
            primary.competency(),
            questionIndexes,
            String.join("；", reasons),
            primary.action(),
            primary.completionCriteria(),
            primary.priority()
        );
    }

    static List<TrainingTaskDTO> buildFallbackTrainingTasks(
            List<QaRecord> qaRecords,
            List<ResolvedQuestionEvaluation> evaluations) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < qaRecords.size() && index < evaluations.size(); index++) {
            ResolvedQuestionEvaluation resolved = evaluations.get(index);
            if (resolved.status() == EvaluationStatus.SCORED && resolved.evaluation() != null
                    && resolveEvaluation(qaRecords.get(index), List.of(resolved.evaluation())).status()
                        == EvaluationStatus.SCORED) {
                indexes.add(index);
            }
        }
        indexes.sort((left, right) -> Integer.compare(
            normalizedScore(evaluations.get(left).evaluation()),
            normalizedScore(evaluations.get(right).evaluation())
        ));

        Map<String, FallbackTrainingTask> tasksByCompetency = new java.util.LinkedHashMap<>();
        for (int index : indexes) {
            QaRecord question = qaRecords.get(index);
            QuestionEvalDTO evaluation = evaluations.get(index).evaluation();
            String competency = question.evaluationGuide() != null
                ? firstNonBlank(question.evaluationGuide().competency(), question.category())
                : firstNonBlank(question.category(), "综合能力");
            List<String> missing = sanitizeItems(evaluation.missingPoints());
            String fallbackReason = !missing.isEmpty()
                ? "本题尚缺：" + String.join("；", missing)
                : firstNonBlank(evaluation.feedback(), "该能力点得分相对较低");
            String action = firstNonBlank(
                evaluation.nextAction(),
                question.evaluationGuide() != null
                    ? question.evaluationGuide().followUpDirection() : null,
                "复盘本题并补充原理、边界和实际验证方式");
            String key = competency.trim().toLowerCase(java.util.Locale.ROOT);
            FallbackTrainingTask task = tasksByCompetency.computeIfAbsent(key,
                ignored -> new FallbackTrainingTask(competency, fallbackReason, action));
            task.questionIndexes().add(question.questionIndex());
            task.missingPoints().addAll(missing);
        }
        List<TrainingTaskDTO> tasks = new ArrayList<>();
        for (FallbackTrainingTask task : tasksByCompetency.values()) {
            if (tasks.size() >= 5) break;
            List<Integer> questionIndexes = task.questionIndexes().stream().distinct().toList();
            List<String> missingPoints = task.missingPoints().stream().distinct().limit(8).toList();
            String questionLabels = questionIndexes.stream()
                .map(index -> Integer.toString(index + 1))
                .collect(Collectors.joining("、"));
            String reason = missingPoints.isEmpty() ? task.fallbackReason()
                : "关联题目尚缺：" + String.join("；", missingPoints);
            String completionCriteria = "重新回答问题 " + questionLabels
                + "，覆盖缺失点并给出至少一个可验证的工程例子。";
            // 数值越大优先级越高；PREP 会选取最高三项用于下一场复测。
            tasks.add(new TrainingTaskDTO(
                task.competency(), questionIndexes, reason, task.action(), completionCriteria,
                5 - tasks.size()
            ));
        }
        return List.copyOf(tasks);
    }

    private record FallbackTrainingTask(
        String competency,
        String fallbackReason,
        String action,
        List<Integer> questionIndexes,
        List<String> missingPoints
    ) {
        private FallbackTrainingTask(String competency, String fallbackReason, String action) {
            this(competency, fallbackReason, action, new ArrayList<>(), new ArrayList<>());
        }
    }

    private static int normalizedScore(QuestionEvalDTO evaluation) {
        return normalizeScoreForRubric(evaluation.score(), resolveRubricLevel(evaluation));
    }

    private List<String> sanitizeItems(List<String> primary, List<String> fallback) {
        List<String> source = (primary != null && !primary.isEmpty()) ? primary : fallback;
        if (source == null || source.isEmpty()) return List.of();
        return source.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim).distinct().limit(8).toList();
    }

    static EvaluationReport buildReport(String sessionId, List<QaRecord> qaRecords,
                                        List<ResolvedQuestionEvaluation> evaluations,
                                        String overallFeedback,
                                        List<String> strengths, List<String> improvements,
                                        List<TrainingTaskDTO> trainingTasks) {
        List<QuestionEvaluation> questionDetails = new ArrayList<>();
        List<ReferenceAnswer> referenceAnswers = new ArrayList<>();
        Map<String, CategoryAccumulator> categoryScoresMap = new HashMap<>();

        long answeredCount = qaRecords.stream()
            .filter(q -> q.userAnswer() != null && !q.userAnswer().isBlank())
            .count();

        int evalSize = evaluations != null ? evaluations.size() : 0;

        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            ResolvedQuestionEvaluation resolved = i < evalSize ? evaluations.get(i) : null;
            QuestionEvalDTO eval = resolved != null ? resolved.evaluation() : null;

            boolean hasAnswer = q.userAnswer() != null && !q.userAnswer().isBlank();
            EvaluationStatus status;
            if (!hasAnswer) {
                status = EvaluationStatus.UNANSWERED;
            } else if (resolved == null || resolved.status() != EvaluationStatus.SCORED || eval == null
                    || resolveEvaluation(q, List.of(eval)).status() != EvaluationStatus.SCORED) {
                status = EvaluationStatus.EVALUATION_FAILED;
            } else {
                status = EvaluationStatus.SCORED;
            }
            int rubricLevel = status == EvaluationStatus.SCORED ? resolveRubricLevel(eval) : 0;
            int score = status == EvaluationStatus.SCORED
                ? normalizeScoreForRubric(eval.score(), rubricLevel)
                : 0;
            String feedback = switch (status) {
                case SCORED -> eval.feedback() != null ? eval.feedback() : "该题已评分，但未生成文字反馈。";
                case UNANSWERED -> "该题未回答，不计入能力得分。";
                case EVALUATION_FAILED -> "该题评估失败，不计入能力得分；可稍后重试评估。";
            };
            String refAnswer = eval != null && eval.referenceAnswer() != null
                ? eval.referenceAnswer() : "";
            List<String> keyPoints = eval != null && eval.keyPoints() != null
                ? eval.keyPoints() : List.of();
            List<String> answerEvidence = status == EvaluationStatus.SCORED
                ? sanitizeEvidence(eval.answerEvidence(), q.userAnswer())
                : List.of();
            List<String> missingPoints = status == EvaluationStatus.SCORED
                ? sanitizeItems(eval.missingPoints())
                : status == EvaluationStatus.UNANSWERED && q.evaluationGuide() != null
                    ? q.evaluationGuide().keyPoints()
                    : List.of();
            List<String> factualRisks = status == EvaluationStatus.SCORED
                ? sanitizeItems(eval.factualRisks())
                : List.of();
            String nextAction = switch (status) {
                case SCORED -> firstNonBlank(
                    eval.nextAction(),
                    q.evaluationGuide() != null ? q.evaluationGuide().followUpDirection() : null,
                    "根据反馈补充原理、边界和验证证据。");
                case UNANSWERED -> "先完成本题回答，再根据关键点逐项自查。";
                case EVALUATION_FAILED -> "稍后重试本题评分，重试成功前不要把 0 分视为能力结论。";
            };

            questionDetails.add(new QuestionEvaluation(
                q.questionIndex(), q.question(), q.category(), q.userAnswer(), score, feedback,
                rubricLevel, answerEvidence, missingPoints, factualRisks, nextAction, status
            ));
            referenceAnswers.add(new ReferenceAnswer(
                q.questionIndex(), q.question(), refAnswer, keyPoints
            ));
            CategoryAccumulator accumulator = categoryScoresMap.computeIfAbsent(
                q.category(), k -> new CategoryAccumulator());
            accumulator.questionCount++;
            if (hasAnswer) accumulator.answeredCount++;
            if (status == EvaluationStatus.SCORED) accumulator.scores.add(score);
        }

        List<CategoryScore> categoryScores = categoryScoresMap.entrySet().stream()
            .map(e -> new CategoryScore(
                e.getKey(),
                (int) e.getValue().scores.stream().mapToInt(Integer::intValue).average().orElse(0),
                e.getValue().questionCount,
                e.getValue().answeredCount,
                e.getValue().scores.size(),
                coverage(e.getValue().scores.size(), e.getValue().answeredCount)
            ))
            .collect(Collectors.toList());

        int scoredQuestions = (int) questionDetails.stream()
            .filter(q -> q.evaluationStatus() == EvaluationStatus.SCORED)
            .count();
        int failedQuestions = (int) questionDetails.stream()
            .filter(q -> q.evaluationStatus() == EvaluationStatus.EVALUATION_FAILED)
            .count();
        int evidenceSupportedQuestions = (int) questionDetails.stream()
            .filter(q -> q.evaluationStatus() == EvaluationStatus.SCORED)
            .filter(q -> q.answerEvidence() != null && !q.answerEvidence().isEmpty())
            .count();
        int overallScore = (int) questionDetails.stream()
            .filter(q -> q.evaluationStatus() == EvaluationStatus.SCORED)
            .mapToInt(QuestionEvaluation::score)
            .average()
            .orElse(0);
        List<TrainingTask> reportTrainingTasks = trainingTasks == null
            ? List.of()
            : trainingTasks.stream()
                .map(item -> new TrainingTask(
                    item.competency(), item.questionIndexes(), item.reason(), item.action(),
                    item.completionCriteria(), item.priority()))
                .toList();

        return new EvaluationReport(
            sessionId, qaRecords.size(), (int) answeredCount, scoredQuestions, failedQuestions,
            evidenceSupportedQuestions,
            coverage(scoredQuestions, (int) answeredCount),
            coverage(evidenceSupportedQuestions, scoredQuestions),
            overallScore, categoryScores, questionDetails,
            overallFeedback,
            strengths != null ? strengths : List.of(),
            improvements != null ? improvements : List.of(),
            reportTrainingTasks,
            referenceAnswers
        );
    }

    private static int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private static int resolveRubricLevel(QuestionEvalDTO evaluation) {
        if (evaluation.rubricLevel() != null
                && evaluation.rubricLevel() >= 0
                && evaluation.rubricLevel() <= 4) {
            return evaluation.rubricLevel();
        }
        int score = clampScore(evaluation.score());
        if (score >= 90) return 4;
        if (score >= 75) return 3;
        if (score >= 60) return 2;
        if (score >= 40) return 1;
        return 0;
    }

    private static int normalizeScoreForRubric(int score, int rubricLevel) {
        int clamped = clampScore(score);
        return switch (rubricLevel) {
            case 0 -> Math.min(clamped, 39);
            case 1 -> Math.max(40, Math.min(clamped, 59));
            case 2 -> Math.max(60, Math.min(clamped, 74));
            case 3 -> Math.max(75, Math.min(clamped, 89));
            case 4 -> Math.max(90, clamped);
            default -> clamped;
        };
    }

    private static List<String> sanitizeEvidence(List<String> evidence, String answer) {
        if (evidence == null || answer == null || answer.isBlank()) return List.of();
        return evidence.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim)
            .filter(answer::contains)
            .distinct()
            .limit(5)
            .toList();
    }

    private static List<String> sanitizeItems(List<String> items) {
        if (items == null) return List.of();
        return items.stream()
            .filter(item -> item != null && !item.isBlank())
            .map(String::trim)
            .distinct()
            .limit(8)
            .toList();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) return candidate.trim();
        }
        return "";
    }

    private static double coverage(int scoredQuestions, int answeredQuestions) {
        return answeredQuestions == 0 ? 0.0 : (double) scoredQuestions / answeredQuestions;
    }

    private String buildCategorySummary(List<QaRecord> qaRecords,
                                        List<ResolvedQuestionEvaluation> evaluations) {
        Map<String, List<Integer>> categoryScores = new HashMap<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            ResolvedQuestionEvaluation resolved = i < evaluations.size() ? evaluations.get(i) : null;
            QuestionEvalDTO eval = resolved != null ? resolved.evaluation() : null;
            if (eval != null && resolved.status() == EvaluationStatus.SCORED
                    && q.userAnswer() != null && !q.userAnswer().isBlank()) {
                categoryScores.computeIfAbsent(q.category(), k -> new ArrayList<>())
                    .add(normalizeScoreForRubric(eval.score(), resolveRubricLevel(eval)));
            }
        }
        return categoryScores.entrySet().stream()
            .map(entry -> {
                int avg = (int) entry.getValue().stream().mapToInt(Integer::intValue).average().orElse(0);
                return String.format("- %s: 平均分 %d, 题数 %d", entry.getKey(), avg, entry.getValue().size());
            })
            .sorted()
            .collect(Collectors.joining("\n"));
    }

    private String buildQuestionHighlights(List<QaRecord> qaRecords,
                                           List<ResolvedQuestionEvaluation> evaluations) {
        List<String> highlights = new ArrayList<>();
        for (int i = 0; i < qaRecords.size(); i++) {
            QaRecord q = qaRecords.get(i);
            ResolvedQuestionEvaluation resolved = i < evaluations.size() ? evaluations.get(i) : null;
            QuestionEvalDTO eval = resolved != null ? resolved.evaluation() : null;
            int score = eval != null && resolved.status() == EvaluationStatus.SCORED
                ? normalizeScoreForRubric(eval.score(), resolveRubricLevel(eval)) : 0;
            String feedback = eval != null && eval.feedback() != null ? eval.feedback() : "";
            EvaluationStatus status = resolved != null ? resolved.status() : EvaluationStatus.EVALUATION_FAILED;
            highlights.add(String.format("questionIndex=%d（显示第%d题）| 状态:%s | 分类:%s\n题目:%s\n"
                    + "原回答:%s\n分数:%d | 反馈:%s\n回答原文证据:%s\n缺失点:%s\n事实风险:%s\n下一步:%s",
                q.questionIndex(), q.questionIndex() + 1, status, q.category(), q.question(),
                orEmpty(q.userAnswer()), score, feedback,
                eval != null ? sanitizeEvidence(eval.answerEvidence(), q.userAnswer()) : List.of(),
                eval != null ? sanitizeItems(eval.missingPoints()) : List.of(),
                eval != null ? sanitizeItems(eval.factualRisks()) : List.of(),
                eval != null ? orEmpty(eval.nextAction()) : ""));
            if (q.evaluationGuide() != null) {
                highlights.add("本题唯一能力点:" + orEmpty(q.evaluationGuide().competency()));
            }
        }
        return String.join("\n\n", highlights);
    }
}
