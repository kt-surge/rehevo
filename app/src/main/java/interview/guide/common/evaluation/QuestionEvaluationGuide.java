package interview.guide.common.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 随题目持久化的评分依据，使出题、追问和评估使用同一能力定义。
 */
public record QuestionEvaluationGuide(
    String competency,
    List<String> keyPoints,
    String followUpDirection,
    String source,
    List<RubricLevel> rubric,
    List<String> trainingTargetIds,
    Boolean trainingTargetsDeclared
) {
  private static final List<RubricLevel> DEFAULT_RUBRIC = List.of(
      new RubricLevel(0, "未回答、答非所问或核心结论错误"),
      new RubricLevel(1, "能提到零散概念，但存在明显错误且无法形成解释"),
      new RubricLevel(2, "核心结论基本正确，能说明常见用法，但缺少原理或边界"),
      new RubricLevel(3, "结论正确完整，能解释原理并结合实际场景说明取舍"),
      new RubricLevel(4, "能分析边界、失败模式、替代方案，并给出可验证的工程证据")
  );

  public QuestionEvaluationGuide {
    competency = competency == null ? "" : competency.trim();
    keyPoints = sanitizeStrings(keyPoints);
    rubric = normalizeRubric(rubric);
    trainingTargetIds = sanitizeStrings(trainingTargetIds);
    trainingTargetsDeclared = Boolean.TRUE.equals(trainingTargetsDeclared) || !trainingTargetIds.isEmpty();
  }

  public QuestionEvaluationGuide(String competency, List<String> keyPoints,
                                 String followUpDirection, String source, List<RubricLevel> rubric) {
    this(competency, keyPoints, followUpDirection, source, rubric, List.of(), false);
  }

  public QuestionEvaluationGuide(String competency, List<String> keyPoints,
                                 String followUpDirection, String source, List<RubricLevel> rubric,
                                 List<String> trainingTargetIds) {
    this(competency, keyPoints, followUpDirection, source, rubric, trainingTargetIds, true);
  }

  public static QuestionEvaluationGuide standard(
      String competency, List<String> keyPoints, String followUpDirection, String source) {
    return new QuestionEvaluationGuide(
        competency,
        keyPoints,
        followUpDirection,
        source,
        DEFAULT_RUBRIC
    );
  }

  private static List<RubricLevel> normalizeRubric(List<RubricLevel> source) {
    Map<Integer, RubricLevel> byLevel = new LinkedHashMap<>();
    if (source != null) {
      source.stream()
          .filter(item -> item != null && item.level() >= 0 && item.level() <= 4)
          .filter(item -> item.criteria() != null && !item.criteria().isBlank())
          .forEach(item -> byLevel.putIfAbsent(
              item.level(), new RubricLevel(item.level(), item.criteria().trim())));
    }
    DEFAULT_RUBRIC.forEach(item -> byLevel.putIfAbsent(item.level(), item));
    return byLevel.values().stream()
        .sorted(Comparator.comparingInt(RubricLevel::level))
        .toList();
  }

  private static List<String> sanitizeStrings(List<String> values) {
    if (values == null) return List.of();
    List<String> sanitized = new ArrayList<>();
    values.stream()
        .filter(value -> value != null && !value.isBlank())
        .map(String::trim)
        .distinct()
        .forEach(sanitized::add);
    return List.copyOf(sanitized);
  }

  public record RubricLevel(
      int level,
      String criteria
  ) {}
}
