package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.CitationValidationReport;
import interview.guide.modules.knowledgebase.model.CitationValidationReport.Status;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 纯语法校验：不猜测来源、不重写答案、不调用模型。 */
public final class RagCitationValidator {
  private static final Pattern REFERENCE = Pattern.compile("\\[E[0-9]+]");

  private RagCitationValidator() {}

  public static CitationValidationReport check(String answer, List<String> suppliedIds) {
    Set<String> supplied = new HashSet<>();
    if (suppliedIds != null) {
      suppliedIds.stream().filter(id -> id != null && !id.isBlank()).forEach(supplied::add);
    }
    if (supplied.isEmpty()) {
      return new CitationValidationReport(Status.DISABLED, List.of(), List.of());
    }
    List<String> referenced = references(answer);
    List<String> unknown = referenced.stream().filter(id -> !supplied.contains(id)).toList();
    Status status = referenced.isEmpty() ? Status.NO_REFERENCES
        : unknown.isEmpty() ? Status.REFERENCES_KNOWN : Status.UNKNOWN_REFERENCES;
    return new CitationValidationReport(status, referenced, unknown);
  }

  /** 识别独立正文标记，跳过转义、反引号代码、围栏代码与显式 Markdown 链接。 */
  static List<String> references(String answer) {
    if (answer == null || answer.isEmpty()) {
      return List.of();
    }
    Set<String> references = new LinkedHashSet<>();
    char fence = 0;
    int fenceLength = 0;
    int inlineLength = 0;
    for (int offset = 0; offset < answer.length();) {
      if (offset == 0 || answer.charAt(offset - 1) == '\n') {
        int start = offset;
        while (start < answer.length() && start - offset < 3 && answer.charAt(start) == ' ') {
          start++;
        }
        if (start == answer.length()) {
          break;
        }
        char first = answer.charAt(start);
        int count = runLength(answer, start, first);
        if ((first == '`' || first == '~') && count >= 3 && inlineLength == 0) {
          if (fence == 0) {
            fence = first;
            fenceLength = count;
          } else if (first == fence && count >= fenceLength
              && answer.substring(start + count, lineEnd(answer, start)).isBlank()) {
            fence = 0;
            fenceLength = 0;
          }
          offset = lineEnd(answer, start) + 1;
          continue;
        }
      }
      if (fence != 0) {
        offset = lineEnd(answer, offset) + 1;
        continue;
      }
      char current = answer.charAt(offset);
      if (current == '`' && !escaped(answer, offset)) {
        int count = runLength(answer, offset, '`');
        if (inlineLength == count) {
          inlineLength = 0;
        } else if (inlineLength == 0 && hasClosingTicks(answer, offset + count, count)) {
          inlineLength = count;
        }
        offset += count;
        continue;
      }
      if (inlineLength == 0 && current == '[' && !escaped(answer, offset)) {
        var matcher = REFERENCE.matcher(answer).region(offset, answer.length());
        if (matcher.lookingAt()) {
          int end = matcher.end();
          boolean explicitLink = end < answer.length() && (answer.charAt(end) == '('
              || (answer.charAt(end) == '[' && !REFERENCE.matcher(answer)
                  .region(end, answer.length()).lookingAt()));
          if (!explicitLink) {
            references.add(answer.substring(offset + 1, end - 1));
          }
          offset = end;
          continue;
        }
      }
      offset++;
    }
    return List.copyOf(references);
  }

  private static int lineEnd(String text, int start) {
    int end = text.indexOf('\n', start);
    return end < 0 ? text.length() : end;
  }

  private static int runLength(String text, int start, char marker) {
    int end = start;
    while (end < text.length() && text.charAt(end) == marker) {
      end++;
    }
    return end - start;
  }

  private static boolean escaped(String text, int offset) {
    int count = 0;
    while (offset > 0 && text.charAt(--offset) == '\\') {
      count++;
    }
    return count % 2 == 1;
  }

  private static boolean hasClosingTicks(String text, int offset, int length) {
    while (offset < text.length()) {
      int next = text.indexOf('`', offset);
      if (next < 0) {
        return false;
      }
      int count = runLength(text, next, '`');
      if (count == length && !escaped(text, next)) {
        return true;
      }
      offset = next + count;
    }
    return false;
  }
}
