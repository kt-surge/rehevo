package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.evaluation.EvaluationReport.CategoryScore;
import interview.guide.common.evaluation.EvaluationReport.EvaluationStatus;
import interview.guide.common.evaluation.UnifiedEvaluationService.BatchReportDTO;
import interview.guide.common.evaluation.UnifiedEvaluationService.QuestionEvalDTO;
import interview.guide.common.evaluation.UnifiedEvaluationService.ResolvedQuestionEvaluation;
import interview.guide.common.evaluation.UnifiedEvaluationService.SummaryDTO;
import interview.guide.common.evaluation.UnifiedEvaluationService.TrainingTaskDTO;
import interview.guide.common.metrics.AppMetricNames;
import interview.guide.common.metrics.ApplicationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("统一面试评估汇总")
class UnifiedEvaluationServiceTest {

    @Test
    @DisplayName("批次缺失题会进行有界单题重试并恢复评分")
    void shouldRetryMissingBatchItemIndividually() throws Exception {
        StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        Resource resource = mock(Resource.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(resourceLoader.getResource(anyString())).thenReturn(resource);
        when(resource.getContentAsString(StandardCharsets.UTF_8)).thenReturn("prompt");

        InterviewEvaluationProperties properties = new InterviewEvaluationProperties();
        properties.setBatchSize(2);
        properties.setFailedItemRetryLimit(1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApplicationMetrics metrics = new ApplicationMetrics(registry);
        UnifiedEvaluationService service = new UnifiedEvaluationService(
            invoker, resourceLoader, properties, metrics, Runnable::run);

        BatchReportDTO partialBatch = new BatchReportDTO(
            80, "批次评价", List.of(), List.of(), List.of(evaluation(0, 80, 3, "回答一")));
        BatchReportDTO recoveredItem = new BatchReportDTO(
            70, "重试评价", List.of(), List.of(), List.of(evaluation(1, 70, 2, "回答二")));
        SummaryDTO summary = new SummaryDTO(
            "总体评价",
            List.of(),
            List.of(),
            List.of(new TrainingTaskDTO(
                "Java 基础",
                List.of(99),
                "第二题得分较低",
                "补充关键概念",
                "重新回答第二题并覆盖关键概念",
                1
            ))
        );
        doReturn(partialBatch, recoveredItem, summary).when(invoker).invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any(), any());

        EvaluationReport report = service.evaluate(
            chatClient,
            "retry-session",
            List.of(
                new QaRecord(0, "问题一", "Java", "回答一"),
                new QaRecord(1, "问题二", "Java", "回答二")
            ),
            ""
        );

        assertThat(report.scoredQuestions()).isEqualTo(2);
        assertThat(report.failedQuestions()).isZero();
        assertThat(report.evaluationCoverage()).isEqualTo(1.0);
        assertThat(report.trainingTasks()).singleElement()
            .extracting(EvaluationReport.TrainingTask::questionIndexes)
            .isEqualTo(List.of(1, 0));
        assertThat(registry.get(AppMetricNames.INTERVIEW_EVALUATION_ITEM_RETRY)
            .tag(AppMetricNames.TAG_STATUS, "recovered").counter().count()).isEqualTo(1.0);
        registry.close();
    }

    @Test
    @DisplayName("模型汇总重复能力点会在服务端合并并保留最高优先级")
    void shouldMergeDuplicateCompetenciesFromSummary() throws Exception {
        StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        Resource resource = mock(Resource.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(resourceLoader.getResource(anyString())).thenReturn(resource);
        when(resource.getContentAsString(StandardCharsets.UTF_8)).thenReturn("prompt");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UnifiedEvaluationService service = new UnifiedEvaluationService(
            invoker, resourceLoader, new InterviewEvaluationProperties(),
            new ApplicationMetrics(registry), Runnable::run);
        BatchReportDTO batch = new BatchReportDTO(60, "批次评价", List.of(), List.of(), List.of(
            evaluation(0, 45, 1, "回答一"),
            evaluation(1, 35, 0, "回答二"),
            evaluation(2, 70, 2, "回答三")
        ));
        SummaryDTO summary = new SummaryDTO("总体评价", List.of(), List.of(), List.of(
            new TrainingTaskDTO("缓存一致性", List.of(0), "缺少失效策略", "补充失效策略", "完成第一题", 2),
            new TrainingTaskDTO("缓存一致性", List.of(1), "缺少并发边界", "补充并发边界", "完成第二题", 5),
            new TrainingTaskDTO("事务边界", List.of(2), "缺少隔离级别", "补充隔离级别", "完成第三题", 4)
        ));
        doReturn(batch, summary).when(invoker).invoke(
            any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any(), any());

        EvaluationReport report = service.evaluate(chatClient, "merge-summary", List.of(
            new QaRecord(0, "缓存题一", "缓存", "回答一"),
            new QaRecord(1, "缓存题二", "缓存", "回答二"),
            new QaRecord(2, "事务题", "事务", "回答三")
        ), "");

        assertThat(report.trainingTasks()).hasSize(2);
        assertThat(report.trainingTasks().getFirst())
            .extracting(
                EvaluationReport.TrainingTask::competency,
                EvaluationReport.TrainingTask::questionIndexes,
                EvaluationReport.TrainingTask::priority,
                EvaluationReport.TrainingTask::action
            )
            .containsExactly("缓存一致性", List.of(0, 1), 5, "补充并发边界");
        assertThat(report.trainingTasks().getFirst().reason()).contains("缺少失效策略", "缺少并发边界");
        assertThat(registry.get(AppMetricNames.INTERVIEW_EVALUATION_TRAINING_TASKS_RAW)
            .tag(AppMetricNames.TAG_SOURCE, "model_summary").counter().count()).isEqualTo(3.0);
        assertThat(registry.get(AppMetricNames.INTERVIEW_EVALUATION_TRAINING_TASKS_NORMALIZED)
            .tag(AppMetricNames.TAG_SOURCE, "model_summary").counter().count()).isEqualTo(2.0);
        assertThat(registry.get(AppMetricNames.INTERVIEW_EVALUATION_TRAINING_TASK_DUPLICATES_COLLAPSED)
            .tag(AppMetricNames.TAG_SOURCE, "model_summary").counter().count()).isEqualTo(1.0);
        registry.close();
    }

    @Test
    @DisplayName("整份报告预算耗尽后不再调用模型，已回答题明确标记为评估失败")
    void shouldStopModelCallsWhenEvaluationDeadlineExpires() throws Exception {
        StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        Resource resource = mock(Resource.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(resourceLoader.getResource(anyString())).thenReturn(resource);
        when(resource.getContentAsString(StandardCharsets.UTF_8)).thenReturn("prompt");

        InterviewEvaluationProperties properties = new InterviewEvaluationProperties();
        properties.setRequestDeadlineSeconds(0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UnifiedEvaluationService service = new UnifiedEvaluationService(
            invoker, resourceLoader, properties, new ApplicationMetrics(registry), Runnable::run);

        EvaluationReport report = service.evaluate(
            chatClient, "expired-session", List.of(
                new QaRecord(0, "问题一", "Java", "回答一"),
                new QaRecord(1, "问题二", "Java", "回答二")
            ), "");

        assertThat(report.scoredQuestions()).isZero();
        assertThat(report.failedQuestions()).isEqualTo(2);
        assertThat(report.evaluationCoverage()).isZero();
        verifyNoInteractions(invoker);
        registry.close();
    }

    @Test
    @DisplayName("批次并发受配置上限约束，单批失败不阻断其余题与汇总")
    void shouldBoundBatchConcurrencyAndIsolateBatchFailure() throws Exception {
        StructuredOutputInvoker invoker = mock(StructuredOutputInvoker.class);
        ResourceLoader resourceLoader = new DefaultResourceLoader();
        ChatClient chatClient = mock(ChatClient.class);

        InterviewEvaluationProperties properties = new InterviewEvaluationProperties();
        properties.setBatchSize(1);
        properties.setMaxConcurrentBatches(2);
        properties.setFailedItemRetryLimit(0);
        properties.setRequestDeadlineSeconds(10);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApplicationMetrics metrics = new ApplicationMetrics(registry);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        // 与生产默认一致：队列有界，但不会让同一波任务在工作线程刚启动时被偶发拒绝。
        executor.setQueueCapacity(16);
        executor.initialize();

        AtomicInteger batchCalls = new AtomicInteger();
        AtomicInteger runningBatches = new AtomicInteger();
        AtomicInteger maxRunningBatches = new AtomicInteger();
        SummaryDTO summary = new SummaryDTO("总体评价", List.of(), List.of(), List.of());
        doAnswer(invocation -> {
            int call = batchCalls.getAndIncrement();
            if (call >= 5) {
                return summary;
            }
            int running = runningBatches.incrementAndGet();
            maxRunningBatches.accumulateAndGet(running, Math::max);
            try {
                Thread.sleep(60);
                if (call == 2) {
                    throw new IllegalStateException("受控批次失败");
                }
                String supplied = invocation.getArgument(2);
                var matcher = Pattern.compile("questionIndex=(\\d+)").matcher(supplied);
                assertThat(matcher.find()).isTrue();
                return new BatchReportDTO(80, "批次评价", List.of(), List.of(),
                    List.of(evaluation(Integer.parseInt(matcher.group(1)), 80, 3, "回答")));
            } finally {
                runningBatches.decrementAndGet();
            }
        }).when(invoker).invoke(any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any(), any());

        try {
            UnifiedEvaluationService service = new UnifiedEvaluationService(
                invoker, resourceLoader, properties, metrics, executor);
            EvaluationReport report = service.evaluate(
                chatClient,
                "bounded-batches",
                List.of(
                    new QaRecord(0, "问题一", "Java", "回答一"),
                    new QaRecord(1, "问题二", "Java", "回答二"),
                    new QaRecord(2, "问题三", "Java", "回答三"),
                    new QaRecord(3, "问题四", "Java", "回答四"),
                    new QaRecord(4, "问题五", "Java", "回答五")
                ),
                ""
            );

            assertThat(maxRunningBatches.get()).isEqualTo(2);
            assertThat(report.scoredQuestions()).isEqualTo(4);
            assertThat(report.failedQuestions()).isEqualTo(1);
            assertThat(registry.get(AppMetricNames.INTERVIEW_EVALUATION_BATCH_TOTAL)
                .tag(AppMetricNames.TAG_STATUS, "success").counter().count()).isEqualTo(4.0);
            assertThat(registry.get(AppMetricNames.INTERVIEW_EVALUATION_BATCH_TOTAL)
                .tag(AppMetricNames.TAG_STATUS, "failure").counter().count()).isEqualTo(1.0);
        } finally {
            executor.shutdown();
            registry.close();
        }
    }

    @Nested
    @DisplayName("评分口径")
    class ScoringRules {

        @Test
        @DisplayName("总分只统计成功评分的已回答题并报告覆盖率")
        void shouldExcludeUnansweredAndFailedItemsFromOverallScore() {
            List<QaRecord> questions = List.of(
                new QaRecord(0, "Java 问题一", "Java", "回答一"),
                new QaRecord(1, "Java 问题二", "Java", null),
                new QaRecord(2, "数据库问题一", "数据库", "回答二"),
                new QaRecord(3, "数据库问题二", "数据库", "回答三")
            );
            List<ResolvedQuestionEvaluation> evaluations = List.of(
                scored(0, 80),
                scored(1, 90),
                failed(),
                scored(3, 120)
            );

            EvaluationReport report = UnifiedEvaluationService.buildReport(
                "session-1", questions, evaluations, "总体评价", List.of("优势"), List.of("建议"), List.of());

            assertThat(report.totalQuestions()).isEqualTo(4);
            assertThat(report.answeredQuestions()).isEqualTo(3);
            assertThat(report.scoredQuestions()).isEqualTo(2);
            assertThat(report.failedQuestions()).isEqualTo(1);
            assertThat(report.evidenceSupportedQuestions()).isEqualTo(2);
            assertThat(report.evaluationCoverage()).isCloseTo(2.0 / 3.0, within(0.0001));
            assertThat(report.evidenceCoverage()).isEqualTo(1.0);
            assertThat(report.overallScore()).isEqualTo(90);
            assertThat(report.questionDetails())
                .extracting(EvaluationReport.QuestionEvaluation::evaluationStatus)
                .containsExactly(
                    EvaluationStatus.SCORED,
                    EvaluationStatus.UNANSWERED,
                    EvaluationStatus.EVALUATION_FAILED,
                    EvaluationStatus.SCORED
                );
            assertThat(report.questionDetails().get(3).score()).isEqualTo(100);

            CategoryScore java = category(report, "Java");
            assertThat(java.score()).isEqualTo(80);
            assertThat(java.questionCount()).isEqualTo(2);
            assertThat(java.answeredQuestionCount()).isEqualTo(1);
            assertThat(java.scoredQuestionCount()).isEqualTo(1);
            assertThat(java.evaluationCoverage()).isEqualTo(1.0);

            CategoryScore database = category(report, "数据库");
            assertThat(database.score()).isEqualTo(100);
            assertThat(database.questionCount()).isEqualTo(2);
            assertThat(database.answeredQuestionCount()).isEqualTo(2);
            assertThat(database.scoredQuestionCount()).isEqualTo(1);
            assertThat(database.evaluationCoverage()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("全部未回答时覆盖率与总分均为零且不产生失败题")
        void shouldHandleCompletelyUnansweredInterview() {
            List<QaRecord> questions = List.of(
                new QaRecord(0, "问题一", "综合", null),
                new QaRecord(1, "问题二", "综合", " ")
            );

            EvaluationReport report = UnifiedEvaluationService.buildReport(
                "session-2", questions, List.of(failed(), failed()), "", List.of(), List.of(), List.of());

            assertThat(report.answeredQuestions()).isZero();
            assertThat(report.scoredQuestions()).isZero();
            assertThat(report.failedQuestions()).isZero();
            assertThat(report.evaluationCoverage()).isZero();
            assertThat(report.overallScore()).isZero();
            assertThat(report.questionDetails())
                .allMatch(item -> item.evaluationStatus() == EvaluationStatus.UNANSWERED);
        }

        @Test
        @DisplayName("评分与 Rubric 等级对齐并过滤回答中不存在的伪证据")
        void shouldAlignScoreWithRubricAndRejectFabricatedEvidence() {
            QuestionEvaluationGuide guide = QuestionEvaluationGuide.standard(
                "缓存一致性",
                List.of("更新数据库", "删除缓存", "失败补偿"),
                "继续追问失败补偿",
                "SKILL_REFERENCE"
            );
            List<QaRecord> questions = List.of(new QaRecord(
                0,
                "数据库与缓存如何保证一致性？",
                "缓存",
                "使用 Cache Aside，先更新数据库再删除缓存。",
                guide
            ));
            QuestionEvalDTO evaluation = new QuestionEvalDTO(
                0,
                55,
                "核心方案正确，但缺少失败补偿。",
                3,
                List.of("先更新数据库", "使用消息队列保证强一致"),
                List.of("删除失败后的补偿"),
                List.of(),
                "补充删除缓存失败时的重试方案",
                "参考答案",
                List.of("Cache Aside")
            );

            EvaluationReport report = UnifiedEvaluationService.buildReport(
                "session-3",
                questions,
                List.of(new ResolvedQuestionEvaluation(evaluation, EvaluationStatus.SCORED)),
                "",
                List.of(),
                List.of(),
                List.of()
            );

            EvaluationReport.QuestionEvaluation detail = report.questionDetails().getFirst();
            assertThat(detail.rubricLevel()).isEqualTo(3);
            assertThat(detail.score()).isEqualTo(75);
            assertThat(detail.answerEvidence()).containsExactly("先更新数据库");
            assertThat(detail.missingPoints()).containsExactly("删除失败后的补偿");
            assertThat(detail.nextAction()).isEqualTo("补充删除缓存失败时的重试方案");
        }
    }

    @Test
    @DisplayName("降级训练任务按能力点合并缺失题并让最低分能力优先复测")
    void shouldMergeFallbackTrainingTasksByCompetencyAndPrioritizeLowerScores() {
        QuestionEvaluationGuide cacheGuide = QuestionEvaluationGuide.standard(
            "缓存一致性", List.of("删除缓存"), "追问失败补偿", "SKILL_REFERENCE");
        QuestionEvaluationGuide transactionGuide = QuestionEvaluationGuide.standard(
            "事务边界", List.of("传播语义"), "追问传播行为", "SKILL_REFERENCE");
        List<QaRecord> records = List.of(
            new QaRecord(0, "缓存题一", "缓存", "回答", cacheGuide),
            new QaRecord(1, "缓存题二", "缓存", "回答", cacheGuide),
            new QaRecord(2, "事务题", "事务", "回答", transactionGuide)
        );
        List<ResolvedQuestionEvaluation> evaluations = List.of(
            new ResolvedQuestionEvaluation(new QuestionEvalDTO(0, 20, "缓存边界不足", 1,
                List.of("回答"), List.of("删除失败补偿"), List.of(), "补充补偿", "", List.of()),
                EvaluationStatus.SCORED),
            new ResolvedQuestionEvaluation(new QuestionEvalDTO(1, 35, "缓存并发不足", 1,
                List.of("回答"), List.of("并发回填旧值"), List.of(), "补充并发边界", "", List.of()),
                EvaluationStatus.SCORED),
            new ResolvedQuestionEvaluation(new QuestionEvalDTO(2, 70, "事务基本正确", 3,
                List.of("回答"), List.of("连接池容量"), List.of(), "补充容量估算", "", List.of()),
                EvaluationStatus.SCORED)
        );

        List<TrainingTaskDTO> tasks = UnifiedEvaluationService.buildFallbackTrainingTasks(records, evaluations);

        assertThat(tasks).hasSize(2);
        assertThat(tasks.getFirst())
            .extracting(TrainingTaskDTO::competency, TrainingTaskDTO::questionIndexes, TrainingTaskDTO::priority)
            .containsExactly("缓存一致性", List.of(0, 1), 5);
        assertThat(tasks.getFirst().reason()).contains("删除失败补偿", "并发回填旧值");
        assertThat(tasks.get(1))
            .extracting(TrainingTaskDTO::competency, TrainingTaskDTO::priority)
            .containsExactly("事务边界", 4);
    }

    private static ResolvedQuestionEvaluation scored(int index, int score) {
        return new ResolvedQuestionEvaluation(
            evaluation(index, score, null, "回答"),
            EvaluationStatus.SCORED
        );
    }

    private static QuestionEvalDTO evaluation(
            int index, int score, Integer rubricLevel, String evidence) {
        return new QuestionEvalDTO(
            index, score, "反馈", rubricLevel, List.of(evidence), List.of(), List.of(),
            "继续补充", "参考答案", List.of("关键点"));
    }

    private static ResolvedQuestionEvaluation failed() {
        return new ResolvedQuestionEvaluation(null, EvaluationStatus.EVALUATION_FAILED);
    }

    private static CategoryScore category(EvaluationReport report, String name) {
        return report.categoryScores().stream()
            .filter(item -> item.category().equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static org.assertj.core.data.Offset<Double> within(double value) {
        return org.assertj.core.data.Offset.offset(value);
    }
}
