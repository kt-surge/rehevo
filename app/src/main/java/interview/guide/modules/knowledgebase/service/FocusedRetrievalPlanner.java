package interview.guide.modules.knowledgebase.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** 只复制原问题中的辅助召回词；完整原问题始终由调用方保留。 */
final class FocusedRetrievalPlanner {
  private static final Pattern SEPARATOR = Pattern.compile(
      "[；;]|[？?。]\\s*|，\\s*(?:以及|同时)|另一个问题[：:]");
  private static final Pattern COMPARISON = Pattern.compile("分别|另一个|与|和|区别|相比|像|同时|[；;]");
  private static final Pattern SUBJECT = Pattern.compile("[A-Za-z][A-Za-z0-9_]{2,}");
  private static final Pattern DEPENDENT = Pattern.compile(
      "^(?:它|它们|这|该|上述|那么|是否|还有|哪一个|另一个|前者|后者)");
  private static final Pattern IDENTIFIER = Pattern.compile(
      "@[A-Za-z][A-Za-z0-9_]*|[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"
          + "|[A-Z][A-Z0-9_]{3,}|(?:[A-Z][a-z0-9]+){2,}");

  private FocusedRetrievalPlanner() {}

  static List<String> plan(String question) {
    if (question == null || question.length() < 32 || question.length() > 512
        || !COMPARISON.matcher(question).find()) {
      return List.of();
    }
    String[] parts = SEPARATOR.split(question);
    List<String> clauses = new ArrayList<>();
    for (String part : parts) {
      String clause = part.trim();
      if (!clause.isEmpty()) {
        clauses.add(clause);
      }
    }
    if (clauses.size() == 2 && clauses.stream().allMatch(clause -> clause.length() >= 10
        && clause.length() <= 240 && SUBJECT.matcher(clause).find()
        && !DEPENDENT.matcher(clause).find())) {
      return List.copyOf(clauses);
    }
    if (clauses.size() > 2 || clauses.stream().anyMatch(clause -> DEPENDENT.matcher(clause).find())) {
      return List.of();
    }
    LinkedHashSet<String> identifiers = new LinkedHashSet<>();
    IDENTIFIER.matcher(question).results().map(result -> result.group())
        .filter(identifier -> identifier.length() <= 100).forEach(identifiers::add);
    return identifiers.size() == 2 ? List.copyOf(identifiers) : List.of();
  }
}
