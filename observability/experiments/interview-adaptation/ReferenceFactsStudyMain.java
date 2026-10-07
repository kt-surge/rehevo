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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.boot.SpringApplication;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/** Actual generation service with one public category and immutable A/B reference resources. */
public final class ReferenceFactsStudyMain {
  private static final Gson JSON = new Gson();
  record Input(String origin, String caseId, String categoryKey, String label, String reference,
               TrainingTask trainingTask) {}

  public static void main(String[] args) throws Exception {
    Path inputFile = Path.of(System.getProperty("refs.input"));
    Path output = Path.of(System.getProperty("refs.output"));
    Path resources = Path.of(System.getProperty("refs.resources"));
    Input input = JSON.fromJson(Files.readString(inputFile), Input.class);
    if (!input.origin().equals("public-reference-boundary-diagnostic-20261006-v1")
        || !List.of("boot-config", "spring-proxy", "mysql-order", "mq-commit").contains(input.caseId())
        || !List.of("SPRING", "MYSQL", "MQ").contains(input.categoryKey())
        || !List.of("spring.md", "mysql.md", "mq.md").contains(input.reference())
        || Files.exists(output.resolve("questions.json"))) {
      throw new IllegalArgumentException("Only frozen public reference cases are supported");
    }
    try (var context = SpringApplication.run(App.class, args)) {
      var registry = context.getBean(LlmProviderRegistry.class);
      var structured = context.getBean(StructuredOutputProperties.class);
      var properties = context.getBean(InterviewQuestionProperties.class);
      if (structured.getStructuredMaxAttempts() != 1 || properties.getFollowUpCount() != 0) {
        throw new IllegalArgumentException("Controlled one-attempt/no-followup settings required");
      }
      var meters = context.getBean(MeterRegistry.class);
      var loader = new FrozenReferences(resources);
      var original = context.getBean(InterviewSkillService.class).getSkill("java-backend");
      var skill = new InterviewSkillService.SkillDTO(original.id(), original.name(), original.description(),
          List.of(new InterviewSkillService.SkillCategoryDTO(input.categoryKey(), input.label(),
              "CORE", input.reference(), true)), true, null, original.persona(), original.display());
      var skills = new ControlledSkill(registry, context.getBean(StructuredOutputInvoker.class), loader,
          context.getBean(PromptSanitizer.class), skill);
      var capture = new Capture(structured, meters, context.getBean(StructuredOutputInvoker.class), output);
      var questions = new InterviewQuestionService(capture, skills, properties, new DefaultResourceLoader(),
          registry, context.getBean(PromptSanitizer.class));
      write(output, "reference-context.json", Map.of("referenceSection", skills.buildReferenceSection(skill,
          Map.of(input.categoryKey(), 1)), "referenceRoot", resources.toAbsolutePath().toString()));
      write(output, "runtime-config.json", Map.of("arm", System.getProperty("refs.arm"),
          "caseId", input.caseId(), "questionProperties", properties, "structured", structured,
          "noSessionWrites", true, "realResumeOrJd", false, "singleCategoryFixture", true,
          "loadedQuestionClass", InterviewQuestionService.class.getProtectionDomain().getCodeSource().getLocation().toString()));
      write(output, "meters-before.json", snapshot(meters));
      long start = System.nanoTime();
      try {
        var result = questions.generateQuestionsBySkill(null, "java-backend", "mid", null, 1,
            List.of(), List.of(input.trainingTask()), null, null);
        write(output, "questions.json", result);
        write(output, "plan.json", new InterviewPlanService().build("java-backend", "mid", result,
            List.of(input.trainingTask())));
        write(output, "result.json", Map.of("status", "finished", "invokerOperations", capture.count,
            "elapsedMs", (System.nanoTime() - start) / 1_000_000.0, "formalEffectOrLatency", false));
      } finally {
        questions.destroy();
        write(output, "meters-after.json", snapshot(meters));
      }
    }
  }

  private static final class FrozenReferences extends DefaultResourceLoader {
    private final Path root;
    FrozenReferences(Path root) { this.root = root; }
    @Override public Resource getResource(String location) {
      String prefix = "classpath:skills/_shared/references/";
      if (location.startsWith(prefix)) {
        String name = location.substring(prefix.length());
        if (List.of("spring.md", "mysql.md", "mq.md").contains(name)) {
          return new FileSystemResource(root.resolve(name));
        }
      }
      return super.getResource(location);
    }
  }

  private static final class ControlledSkill extends InterviewSkillService {
    private final SkillDTO skill;
    ControlledSkill(LlmProviderRegistry registry, StructuredOutputInvoker invoker,
                    FrozenReferences loader, PromptSanitizer sanitizer, SkillDTO skill) throws IOException {
      super(registry, invoker, loader, sanitizer);
      this.skill = skill;
    }
    @Override public SkillDTO getSkill(String id) {
      if (!id.equals("java-backend")) throw new IllegalArgumentException("Frozen Skill required");
      return skill;
    }
  }

  private static final class Capture extends StructuredOutputInvoker {
    private final StructuredOutputInvoker delegate;
    private final Path output;
    private int count;
    Capture(StructuredOutputProperties props, MeterRegistry meters, StructuredOutputInvoker delegate, Path output) {
      super(props, meters);
      this.delegate = delegate;
      this.output = output;
    }
    @Override public <T> T invoke(ChatClient client, String system, String user, BeanOutputConverter<T> converter,
        ErrorCode code, String prefix, String label, Logger logger) {
      if (++count > 1) throw new BusinessException(code, "参考材料诊断操作上限已达");
      try {
        write(output, "supplied-prompt.json", Map.of("system", system, "user", user,
            "securitySuffix", PromptSecurityConstants.ANTI_INJECTION_INSTRUCTION, "context", label));
        T result = delegate.invoke(client, system, user, converter, code, prefix, label, logger);
        write(output, "parsed-result.json", result);
        return result;
      } catch (IOException error) {
        throw new BusinessException(code, "诊断证据写入失败");
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
      List<Map<String, Object>> values = new ArrayList<>();
      meter.measure().forEach(value -> values.add(Map.of("statistic", value.getStatistic().name(), "value", value.getValue())));
      item.put("measurements", values);
      result.add(item);
    });
    return result;
  }

  private static void write(Path output, String name, Object value) throws IOException {
    Files.writeString(output.resolve(name), JSON.toJson(value), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }
}
