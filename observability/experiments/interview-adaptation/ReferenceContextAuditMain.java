package interview.guide.modules.interview.skill;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

/** Offline production reference loader audit; no model, Spring application or database. */
public final class ReferenceContextAuditMain {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(System.getProperty("context.repository"));
    Path output = Path.of(System.getProperty("context.output"));
    var result = new LinkedHashMap<String, Object>();
    for (String arm : List.of("baseline", "candidate")) {
      String variant = arm.equals("candidate")
          ? System.getProperty("context.candidateSource", "reference-facts-source-candidate-20261006-r1")
          : "reference-facts-source-baseline-20261006-r1";
      Path references = root.resolve("observability/experiments/voice-frame-pipeline/runs/"
          + variant + "/references");
      var loader = new DefaultResourceLoader() {
        @Override public Resource getResource(String location) {
          String prefix = "classpath:skills/_shared/references/";
          if (location.startsWith(prefix)) {
            String name = location.substring(prefix.length());
            if (List.of("spring.md", "mysql.md", "mq.md").contains(name)) {
              return new FileSystemResource(references.resolve(name));
            }
          }
          return super.getResource(location);
        }
      };
      // These dependencies are not used by loading/buildEvaluationReferenceSection.
      var skills = new InterviewSkillService(null, null, loader, null);
      skills.loadPresetSkills();
      var skill = skills.getSkill("java-backend");
      String evaluation = skills.buildEvaluationReferenceSection("java-backend");
      Map<String, Integer> allocation = new LinkedHashMap<>();
      skill.categories().forEach(category -> allocation.put(category.key(), 1));
      String generation = skills.buildReferenceSection(skill, allocation);
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("normalPresetCategories", skill.categories());
      detail.put("sourceVariant", variant);
      detail.put("presetCount", skills.getAllSkills().size());
      detail.put("evaluationReference", evaluation);
      detail.put("generationReference", generation);
      detail.put("evaluationChars", evaluation.length());
      detail.put("generationChars", generation.length());
      detail.put("evaluationTruncated", evaluation.contains("references 已截断"));
      detail.put("evaluationCategoryHeaders", evaluation.lines().filter(line -> line.startsWith("### ")).toList());
      result.put(arm, detail);
    }
    result.put("externalModelCalls", 0);
    result.put("normalPresetActualLoader", true);
    result.put("actualInferenceQuality", "not tested by offline loading");
    Files.writeString(output.resolve("normal-reference-context-audit.json"), new Gson().toJson(result),
        StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
  }
}
