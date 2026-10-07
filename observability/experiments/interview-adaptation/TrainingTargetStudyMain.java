package interview.guide.modules.interview.service;

import com.google.gson.Gson;
import interview.guide.App;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.ai.PromptSecurityConstants;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.ai.StructuredOutputProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewReportDTO.TrainingTask;
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

/** One controlled default-model generation through the production question/plan services. */
public final class TrainingTargetStudyMain {
  private static final Gson JSON = new Gson();
  record Study(String payloadOrigin, String skillId, String difficulty, int questionCount,
               List<TrainingTask> priorTrainingTasks) {}

  public static void main(String[] args) throws Exception {
    Path input = Path.of(System.getProperty("targets.inputs"));
    Path output = Path.of(System.getProperty("targets.output"));
    Study study = JSON.fromJson(Files.readString(input, StandardCharsets.UTF_8), Study.class);
    if (!study.payloadOrigin().equals("sealed-public-evaluation-deadline-live-r1")
        || !study.skillId().equals("java-backend") || !study.difficulty().equals("mid")
        || study.questionCount() != 3 || study.priorTrainingTasks().isEmpty()
        || study.priorTrainingTasks().size() > 3
        || study.priorTrainingTasks().stream().anyMatch(task -> !task.questionIndexes().equals(List.of(0)))
        || Files.exists(output.resolve("questions.json"))) {
      throw new IllegalArgumentException("Only the frozen public training-task diagnostic is supported");
    }
    try (var context = SpringApplication.run(App.class, args)) {
      var meters = context.getBean(MeterRegistry.class);
      var properties = context.getBean(InterviewQuestionProperties.class);
      var capture = new CapturingInvoker(context.getBean(StructuredOutputProperties.class), meters,
          context.getBean(StructuredOutputInvoker.class), output);
      var service = new InterviewQuestionService(capture, context.getBean(InterviewSkillService.class),
          properties, new DefaultResourceLoader(), context.getBean(LlmProviderRegistry.class),
          context.getBean(PromptSanitizer.class));
      write(output, "runtime-config.json", Map.of("arm", System.getProperty("targets.arm"),
          "questionProperties", properties, "structured", context.getBean(StructuredOutputProperties.class),
          "noSessionWrites", true, "realResumeOrJd", false,
          "loadedQuestionClass", InterviewQuestionService.class.getProtectionDomain().getCodeSource().getLocation().toString(),
          "loadedPlanClass", InterviewPlanService.class.getProtectionDomain().getCodeSource().getLocation().toString()));
      write(output, "meters-before.json", snapshot(meters));
      long started = System.nanoTime();
      try {
        var questions = service.generateQuestionsBySkill(null, study.skillId(), study.difficulty(),
            null, study.questionCount(), List.of(), study.priorTrainingTasks(), null, null);
        var plan = new InterviewPlanService().build(study.skillId(), study.difficulty(), questions,
            study.priorTrainingTasks());
        write(output, "questions.json", questions);
        write(output, "plan.json", plan);
        write(output, "result.json", Map.of("status", "finished", "invokerOperations", capture.count,
            "elapsedMs", (System.nanoTime() - started) / 1_000_000.0,
            "mainQuestions", questions.stream().filter(q -> !q.isFollowUp()).count(),
            "arrangedTargets", plan.prioritizedCompetencies(), "requestedTargets", plan.requestedFocusCompetencies(),
            "semanticQualityRequiresReview", true, "notFormalEffectOrLatency", true));
      } finally {
        service.destroy();
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
    Files.writeString(output.resolve(name), JSON.toJson(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
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
      if (++count > 1) throw new BusinessException(code, "诊断调用上限已达");
      try {
        write(output, "01-supplied-prompt.json", Map.of("system", system, "user", user,
            "securitySuffix", PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION,
            "context", label, "at", Instant.now().toString(),
            "note", "repair retries remain in the production invoker; no transport headers"));
        T result = delegate.invoke(client, system, user, converter, code, prefix, label, logger);
        write(output, "01-parsed-result.json", result);
        return result;
      } catch (IOException error) {
        throw new BusinessException(code, "诊断证据写入失败");
      }
    }
  }
}
