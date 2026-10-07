package interview.guide.modules.knowledgebase.service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 有限原文预览；不增加返回字符预算，不影响实际生成上下文。 */
final class RagEvidencePreview {
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z_0-9]{2,}");

  private RagEvidencePreview() {}

  static String from(String text, String question, int maxChars) {
    if (text == null || text.length() <= maxChars) {
      return text;
    }
    Set<String> identifiers = new LinkedHashSet<>();
    var matcher = IDENTIFIER.matcher(question == null ? "" : question);
    while (matcher.find()) {
      identifiers.add(matcher.group());
    }
    var terms = identifiers.stream().sorted(Comparator
        .comparing((String term) -> !term.equals(term.toUpperCase(Locale.ROOT)))
        .thenComparing(Comparator.comparingInt(String::length).reversed())).toList();
    String lower = text.toLowerCase(Locale.ROOT);
    var positions = terms.stream().map(term -> lower.indexOf(term.toLowerCase(Locale.ROOT)))
        .filter(position -> position >= 0).distinct().limit(2).toList();
    if (positions.isEmpty()) {
      return text.substring(0, maxChars - 1) + "…";
    }
    if (positions.size() == 1 || Math.abs(positions.getFirst() - positions.getLast()) < maxChars / 2) {
      return excerpt(text, positions.getFirst(), maxChars);
    }
    int each = (maxChars - 3) / 2;
    String result = excerpt(text, positions.getFirst(), each) + "\n…\n"
        + excerpt(text, positions.getLast(), each);
    return result;
  }

  private static String excerpt(String text, int position, int budget) {
    int start = Math.max(0, position - Math.min(30, budget / 4));
    int prefix = start > 0 ? 1 : 0;
    int end = Math.min(text.length(), start + budget - prefix - 1);
    return (prefix == 1 ? "…" : "") + text.substring(start, end)
        + (end < text.length() ? "…" : "");
  }
}
