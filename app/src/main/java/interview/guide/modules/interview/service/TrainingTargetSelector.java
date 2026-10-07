package interview.guide.modules.interview.service;

import interview.guide.modules.interview.model.InterviewReportDTO.TrainingTask;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 出题和计划共用训练目标定义，标识不依赖生成题目的显示名称。 */
public final class TrainingTargetSelector {
  private TrainingTargetSelector() {}

  public record Target(String id, TrainingTask task) {}

  public static List<Target> select(List<TrainingTask> tasks) {
    if (tasks == null) return List.of();
    var byCompetency = new LinkedHashMap<String, TrainingTask>();
    tasks.stream()
        .filter(task -> task != null && task.competency() != null && !task.competency().isBlank())
        .sorted(Comparator.comparingInt(TrainingTask::priority).reversed()
            .thenComparing(task -> normalize(task.competency())))
        .forEach(task -> byCompetency.putIfAbsent(normalize(task.competency()), task));
    return byCompetency.values().stream().limit(3)
        .map(task -> new Target(idOf(task), task)).toList();
  }

  private static String idOf(TrainingTask task) {
    String definition = "rehevo-training-target-v1\n" + normalize(task.competency())
        + "\n" + clean(task.action()) + "\n" + clean(task.completionCriteria());
    return "training:" + UUID.nameUUIDFromBytes(definition.getBytes(StandardCharsets.UTF_8));
  }

  static String normalize(String value) {
    return clean(value).toLowerCase(Locale.ROOT);
  }

  private static String clean(String value) {
    return value == null ? "" : value.trim();
  }
}
