package interview.guide.modules.knowledgebase.service;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从当前候选中预留标识对应的完整块；不读取金标、不补发检索、不修改原文。 */
final class IdentifierEvidenceSelector {
  private static final Pattern TOKEN = Pattern.compile(
      "@?[A-Za-z][A-Za-z0-9_]*(?:[.-][A-Za-z][A-Za-z0-9_]*)*");

  private IdentifierEvidenceSelector() {}

  static Selection select(String question, List<Document> candidates, int topK, int budget,
                          ToIntFunction<String> countTokens) {
    List<String> terms = anchors(question);
    List<Document> reserved = new ArrayList<>();
    List<Reservation> reservations = new ArrayList<>();
    int reservationLimit = Math.min(4, Math.max(0, topK / 2));
    for (String term : terms) {
      if (reserved.size() >= reservationLimit) {
        break;
      }
      Pattern mention = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(term)
          + "(?![A-Za-z0-9_])");
      Pattern heading = Pattern.compile("(?:^|\\n)" + Pattern.quote(term) + "\\s*\\n");
      Pattern signature = Pattern.compile("\\b" + Pattern.quote(term));
      Document chosen = null;
      boolean detailHeading = false;
      for (Document candidate : candidates) {
        String text = candidate.getText();
        if (text == null || !mention.matcher(text).find()) {
          continue;
        }
        if (chosen == null) {
          chosen = candidate;
        }
        if (hasDetailHeading(text, term, heading, signature)) {
          chosen = candidate;
          detailHeading = true;
          break;
        }
      }
      if (chosen == null) {
        continue;
      }
      String chosenId = chosen.getId();
      if (reserved.stream().noneMatch(document -> document.getId().equals(chosenId))) {
        reserved.add(chosen);
        reservations.add(new Reservation(term, chosenId, detailHeading));
      }
    }
    List<Document> ordered = new ArrayList<>(reserved);
    candidates.stream().filter(candidate -> reserved.stream()
        .noneMatch(document -> document.getId().equals(candidate.getId()))).forEach(ordered::add);
    List<Document> selected = new ArrayList<>();
    int used = 0;
    for (Document document : ordered) {
      if (selected.size() >= topK) {
        break;
      }
      int tokens = countTokens.applyAsInt(document.getText());
      if (tokens <= budget - used) {
        selected.add(document);
        used += tokens;
      }
    }
    return new Selection(selected, terms, reservations, used);
  }

  /** 等价于旧的两段有界通配扫描，避免对每个起点重复尝试 121 * 181 种切分。 */
  private static boolean hasDetailHeading(String text, String term, Pattern heading,
                                         Pattern signature) {
    Matcher headings = heading.matcher(text);
    Matcher signatures = signature.matcher(text).useTransparentBounds(true);
    int searchFrom = 0;
    while (searchFrom <= text.length() && headings.find(searchFrom)) {
      int start = headings.end();
      int firstLineEnd = advance(text, start, 120, true);
      int lastSignatureStart = advance(text, firstLineEnd, 180, false);
      signatures.region(start, Math.min(text.length(), lastSignatureStart + term.length()));
      while (signatures.find()) {
        if (signatures.start() > lastSignatureStart) {
          break;
        }
        int after = signatures.end();
        while (after < text.length() && isRegexWhitespace(text.charAt(after))) {
          after++;
        }
        if (after < text.length() && text.charAt(after) == '(') {
          return true;
        }
      }
      // 上个匹配吃掉的换行也可能是下个标题的开头，不能直接从 end() 继续。
      searchFrom = headings.start() + 1;
    }
    return false;
  }

  private static int advance(String text, int start, int limit, boolean stopAtNewline) {
    int cursor = start;
    for (int count = 0; count < limit && cursor < text.length(); count++) {
      if (stopAtNewline && text.charAt(cursor) == '\n') {
        break;
      }
      // Java Pattern 的字符类重复次数按码点计算，不能用 UTF-16 char 数替代。
      cursor += Character.charCount(text.codePointAt(cursor));
    }
    return cursor;
  }

  private static boolean isRegexWhitespace(char value) {
    return value == ' ' || value == '\t' || value == '\n' || value == '\r'
        || value == '\f' || value == '\u000b';
  }

  static List<String> anchors(String question) {
    if (question == null || question.length() > 512) {
      return List.of();
    }
    Map<String, Integer> terms = new LinkedHashMap<>();
    TOKEN.matcher(question).results().forEach(match -> {
      String full = match.group();
      String term = full.substring(full.lastIndexOf('.') + 1).replaceFirst("^@", "");
      if (term.length() < 3 || term.length() > 100) {
        return;
      }
      int priority = full.contains(".") ? 3 : full.startsWith("@") || full.contains("_")
          || full.matches("[A-Z0-9_]+") ? 2 : 1;
      terms.merge(term, priority, Math::max);
    });
    if (terms.size() > 8) {
      return List.of();
    }
    return terms.entrySet().stream()
        .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
        .map(Map.Entry::getKey).toList();
  }

  record Reservation(String identifier, String chunkId, boolean detailHeading) {}

  record Selection(List<Document> documents, List<String> anchors,
                   List<Reservation> reservations, int tokenEstimate) {
    Selection {
      documents = List.copyOf(documents);
      anchors = List.copyOf(anchors);
      reservations = List.copyOf(reservations);
    }
  }
}
