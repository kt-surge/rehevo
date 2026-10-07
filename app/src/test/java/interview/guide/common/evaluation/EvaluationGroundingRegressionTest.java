package interview.guide.common.evaluation;

import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.evaluation.EvaluationReport.EvaluationStatus;
import interview.guide.common.evaluation.UnifiedEvaluationService.BatchReportDTO;
import interview.guide.common.evaluation.UnifiedEvaluationService.QuestionEvalDTO;
import interview.guide.common.evaluation.UnifiedEvaluationService.SummaryDTO;
import interview.guide.common.evaluation.UnifiedEvaluationService.TrainingTaskDTO;
import interview.guide.common.metrics.ApplicationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

@DisplayName("真实负结果对应的评估来源与题号回归")
class EvaluationGroundingRegressionTest {
  private StructuredOutputInvoker invoker;
  private ChatClient client;
  private SimpleMeterRegistry registry;
  private InterviewEvaluationProperties properties;

  @BeforeEach
  void setup() {
    invoker = mock(StructuredOutputInvoker.class);
    client = mock(ChatClient.class);
    registry = new SimpleMeterRegistry();
    properties = new InterviewEvaluationProperties();
    properties.setFailedItemRetryLimit(0);
  }

  @AfterEach
  void closeRegistry() {
    registry.close();
  }

  @Test
  @DisplayName("乱序返回仍按稀疏原始索引关联而非数组位置")
  void unorderedSparseIndexes() throws Exception {
    stub(batch(score(9, 30, "乙回答"), score(4, 80, "甲回答")), summary());
    EvaluationReport report = evaluate(List.of(
        new QaRecord(4, "甲问题", "甲能力", "甲回答"),
        new QaRecord(9, "乙问题", "乙能力", "乙回答")));
    assertThat(report.questionDetails()).extracting(EvaluationReport.QuestionEvaluation::score)
        .containsExactly(80, 30);
    assertThat(report.evidenceSupportedQuestions()).isEqualTo(2);
  }

  @Test
  @DisplayName("重复索引拒绝歧义结果并保留缺失题失败状态")
  void duplicateIndexes() throws Exception {
    stub(batch(score(4, 80, "甲回答"), score(4, 30, "乙回答")), summary());
    EvaluationReport report = evaluate(List.of(
        new QaRecord(4, "甲问题", "甲能力", "甲回答"),
        new QaRecord(9, "乙问题", "乙能力", "乙回答")));
    assertThat(report.scoredQuestions()).isZero();
    assertThat(report.failedQuestions()).isEqualTo(2);
    assertThat(report.trainingTasks()).isEmpty();
  }

  @Test
  @DisplayName("空引用与伪引用不能形成评分或污染综合结论与训练")
  void unsupportedEvidence() throws Exception {
    BatchReportDTO fabricated = new BatchReportDTO(65, "候选人已列举线程池参数", List.of("线程池熟练"),
        List.of("线程池待优化"), List.of(score(0, 65, "corePoolSize"), score(1, 65, null)));
    stub(fabricated, new SummaryDTO("线程池熟练", List.of(), List.of(), List.of(
        new TrainingTaskDTO("线程池", List.of(0), "凭空出现", "复习", "完成", 5))));
    EvaluationReport report = evaluate(List.of(
        new QaRecord(0, "ACK 如何处理", "消息可靠性", "我认为事务能避免重复处理"),
        new QaRecord(1, "代理如何生效", "事务", "同类调用尚未验证")));
    assertThat(report.scoredQuestions()).isZero();
    assertThat(report.failedQuestions()).isEqualTo(2);
    assertThat(report.overallFeedback()).doesNotContain("线程池");
    assertThat(report.strengths()).isEmpty();
    assertThat(report.trainingTasks()).isEmpty();
  }

  @Test
  @DisplayName("模型任务指向未回答题时只从已验证评分生成训练任务")
  void unansweredTaskIsRejected() throws Exception {
    stub(batch(score(0, 50, "事务未验证"), score(1, 0, "没有回答")),
        new SummaryDTO("总体评价", List.of(), List.of(), List.of(
            new TrainingTaskDTO("线程池", List.of(1), "Q1得65分", "复习参数", "列出参数", 5))));
    EvaluationReport report = evaluate(List.of(
        new QaRecord(0, "事务题", "事务", "事务未验证"),
        new QaRecord(1, "后续题", "事务", null)));
    assertThat(report.questionDetails().get(1).evaluationStatus()).isEqualTo(EvaluationStatus.UNANSWERED);
    assertThat(report.trainingTasks()).singleElement()
        .extracting(EvaluationReport.TrainingTask::questionIndexes).isEqualTo(List.of(0));
    assertThat(report.trainingTasks().getFirst().competency()).isEqualTo("事务");
  }

  @Test
  @DisplayName("已有评分能力点时不接受模型改成无关训练能力")
  void mismatchedCompetencyIsRejected() throws Exception {
    stub(batch(score(0, 50, "事务未验证")), new SummaryDTO("总体评价", List.of(), List.of(), List.of(
        new TrainingTaskDTO("线程池", List.of(0), "不存在的缺口", "复习参数", "列出参数", 5))));
    QuestionEvaluationGuide guide = QuestionEvaluationGuide.standard(
        "事务代理边界", List.of("同类调用"), "验证代理路径", "CONTROLLED");
    EvaluationReport report = evaluate(List.of(new QaRecord(0, "事务题", "事务", "事务未验证", guide)));
    assertThat(report.trainingTasks()).singleElement()
        .extracting(EvaluationReport.TrainingTask::competency).isEqualTo("事务代理边界");
  }

  @Test
  @DisplayName("单题重试返回其他题号时不能恢复本题评分")
  void retryWrongQuestionIndex() throws Exception {
    properties.setFailedItemRetryLimit(1);
    stub(batch(), batch(score(99, 80, "事务未验证")), summary());
    EvaluationReport report = evaluate(List.of(new QaRecord(7, "事务题", "事务", "事务未验证")));
    assertThat(report.scoredQuestions()).isZero();
    assertThat(report.failedQuestions()).isEqualTo(1);
  }

  @Test
  @DisplayName("实际汇总提示明确原始索引并包含完整原回答及有效证据")
  void summaryReceivesOriginalAnswerAndIndex() throws Exception {
    String answer = "公开背景".repeat(35) + "提交后ACK前退出会再次投递";
    List<String> prompts = new ArrayList<>();
    doAnswer(call -> {
      prompts.add(call.getArgument(2));
      return prompts.size() == 1 ? batch(score(7, 50, "提交后ACK前退出会再次投递")) : summary();
    }).when(invoker).invoke(any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any(), any());
    evaluate(List.of(new QaRecord(7, "Redis 消息题", "可靠投递", answer)));
    assertThat(prompts).hasSize(2);
    assertThat(prompts.getFirst()).contains("questionIndex=7");
    assertThat(prompts.get(1)).contains("questionIndex=7", answer, "SCORED", "提交后ACK前退出会再次投递");
  }

  private EvaluationReport evaluate(List<QaRecord> records) throws Exception {
    UnifiedEvaluationService service = new UnifiedEvaluationService(invoker, new DefaultResourceLoader(),
        properties, new ApplicationMetrics(registry), Runnable::run);
    return service.evaluate(client, "controlled-grounding", records, "");
  }

  private void stub(Object first, Object... following) {
    doReturn(first, following).when(invoker).invoke(
        any(), anyString(), anyString(), any(), any(), anyString(), anyString(), any(), any());
  }

  private BatchReportDTO batch(QuestionEvalDTO... scores) {
    return new BatchReportDTO(50, "批次评价", List.of(), List.of(), List.of(scores));
  }

  private SummaryDTO summary() {
    return new SummaryDTO("总体评价", List.of(), List.of(), List.of());
  }

  private QuestionEvalDTO score(int index, int value, String evidence) {
    return new QuestionEvalDTO(index, value, "尚缺边界验证", null,
        evidence == null ? List.of() : List.of(evidence), List.of("边界验证"), List.of(),
        "补充故障验证", "参考答案", List.of("边界"));
  }
}
