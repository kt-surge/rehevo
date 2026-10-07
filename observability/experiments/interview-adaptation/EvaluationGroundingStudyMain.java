package interview.guide.experiments;

import com.google.gson.Gson;
import interview.guide.App;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.StructuredOutputProperties;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.evaluation.InterviewEvaluationProperties;
import interview.guide.common.evaluation.QaRecord;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.interview.skill.InterviewSkillService;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Captures only controlled prompts and parsed results; uses the actual default registry/invoker. */
public final class EvaluationGroundingStudyMain {
  private static final Gson JSON = new Gson();
  record Study(String payloadOrigin, String skillId, List<QaRecord> qaRecords) {}

  public static void main(String[] args) throws Exception {
    Path input = Path.of(System.getProperty("grounding.inputs"));
    Path output = Path.of(System.getProperty("grounding.output"));
    var toolAccess = LlmProviderRegistry.ToolAccess.valueOf(System.getProperty("grounding.toolAccess", "DEFAULT"));
    Study study = JSON.fromJson(Files.readString(input, StandardCharsets.UTF_8), Study.class);
    if (!study.payloadOrigin().equals("sealed-public-training-history-product-r1")
        || !study.skillId().equals("java-backend") || study.qaRecords().size() != 2
        || study.qaRecords().getFirst().questionIndex() != 0
        || study.qaRecords().getFirst().userAnswer() == null
        || study.qaRecords().get(1).questionIndex() != 1
        || study.qaRecords().get(1).userAnswer() != null || Files.exists(output.resolve("report.json"))) {
      throw new IllegalArgumentException("Only the frozen two-question public diagnostic is supported");
    }
    try (var context = SpringApplication.run(App.class, args)) {
      var registry = context.getBean(LlmProviderRegistry.class);
      var meters = context.getBean(MeterRegistry.class);
      var properties = context.getBean(InterviewEvaluationProperties.class);
      var structured = context.getBean(StructuredOutputProperties.class);
      var advisors = context.getBean(LlmProviderProperties.class).getAdvisors();
      write(output, "runtime-config.json", Map.of("arm", System.getProperty("grounding.arm"),
          "evaluation", properties, "structured", structured, "advisors", advisors,
          "inputOrigin", study.payloadOrigin(), "noSessionWrites", true, "toolAccess", toolAccess,
          "loadedEvaluationClass", UnifiedEvaluationService.class.getProtectionDomain()
              .getCodeSource().getLocation().toString()));
      String reference = context.getBean(InterviewSkillService.class)
          .buildEvaluationReferenceSectionSafe(study.skillId());
      write(output, "reference-context.json", Map.of("skillId", study.skillId(), "referenceContext", reference));
      var capture = new CapturingInvoker(structured, meters,
          context.getBean(StructuredOutputInvoker.class), output);
      var service = new UnifiedEvaluationService(capture, new DefaultResourceLoader(), properties,
          context.getBean(ApplicationMetrics.class),
          context.getBean("interviewEvaluationExecutor", Executor.class));
      write(output, "meters-before.json", snapshot(meters));
      long startedAt = System.nanoTime();
      try {
        var report = service.evaluate(registry.getChatClientOrDefault(null, toolAccess),
            "controlled-grounding-" + System.getProperty("grounding.arm"), study.qaRecords(), "", reference);
        write(output, "report.json", report);
        write(output, "result.json", Map.of("status", "finished", "invokerOperations", capture.count,
            "elapsedMs", (System.nanoTime() - startedAt) / 1_000_000.0,
            "answered", report.answeredQuestions(), "scored", report.scoredQuestions(),
            "failed", report.failedQuestions(), "evidenceSupported", report.evidenceSupportedQuestions()));
      } finally {
        write(output, "meters-after.json", snapshot(meters));
      }
    }
  }

  private static List<Map<String, Object>> snapshot(MeterRegistry meters) {
    List<Map<String, Object>> result = new ArrayList<>();
    meters.getMeters().stream().filter(m -> m.getId().getName().startsWith("gen_ai.")
        || m.getId().getName().startsWith("app.ai.structured_output")).forEach(meter -> {
          Map<String, Object> item = new LinkedHashMap<>();
          item.put("name", meter.getId().getName());
          item.put("tags", meter.getId().getTags().stream()
              .map(tag -> Map.of("key", tag.getKey(), "value", tag.getValue())).toList());
          List<Map<String, Object>> measurements = new ArrayList<>();
          meter.measure().forEach(value -> measurements.add(Map.of("statistic", value.getStatistic().name(),
              "value", value.getValue())));
          item.put("measurements", measurements);
          result.add(item);
        });
    return result;
  }

  private static void write(Path output, String name, Object value) throws IOException {
    Files.writeString(output.resolve(name), JSON.toJson(value), StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW);
  }

  private static final class CapturingInvoker extends StructuredOutputInvoker {
    private final StructuredOutputInvoker delegate;
    private final Path output;
    private int count;

    private CapturingInvoker(StructuredOutputProperties properties, MeterRegistry meters,
                             StructuredOutputInvoker delegate, Path output) {
      super(properties, meters);
      this.delegate = delegate;
      this.output = output;
    }

    @Override
    public <T> T invoke(ChatClient client, String system, String user, BeanOutputConverter<T> converter,
                        ErrorCode code, String prefix, String label, Logger logger) {
      return invoke(client, system, user, converter, code, prefix, label, logger, () -> true);
    }

    @Override
    public <T> T invoke(ChatClient client, String system, String user, BeanOutputConverter<T> converter,
                        ErrorCode code, String prefix, String label, Logger logger,
                        BooleanSupplier withinBudget) {
      int ordinal = ++count;
      if (ordinal > 3) throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "诊断调用上限已达");
      String stem = String.format("%02d", ordinal);
      try {
        write(output, stem + "-supplied-prompt.json", Map.of("system", system, "user", user,
            "securitySuffix", PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION,
            "context", label, "at", Instant.now().toString(),
            "note", "before invocation; repair retries may append instructions; no transport headers"));
        T result = delegate.invoke(client, system, user, converter, code, prefix, label, logger, withinBudget);
        write(output, stem + "-parsed-result.json", result);
        return result;
      } catch (IOException error) {
        throw new BusinessException(ErrorCode.INTERVIEW_EVALUATION_FAILED, "诊断证据写入失败");
      }
    }
  }
}
