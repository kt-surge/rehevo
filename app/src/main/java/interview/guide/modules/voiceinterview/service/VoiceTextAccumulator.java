package interview.guide.modules.voiceinterview.service;

import java.util.function.Consumer;

/** 单轮有界问题缓冲：确认完整主问题后才允许播报，绝不硬切问题条件。 */
final class VoiceTextAccumulator {

  private static final String TERMINAL_PUNCTUATION = "。！？；!?;.";
  private final int maxChars;
  private final Consumer<String> onSentence;
  private final StringBuilder raw = new StringBuilder();
  private String content = "";
  private int emittedSentenceCount;
  private boolean finished;

  VoiceTextAccumulator(int maxChars, Consumer<String> onSentence) {
    this.maxChars = Math.max(2, maxChars);
    this.onSentence = onSentence;
  }

  void append(String token) {
    if (finished || !content.isEmpty() || token == null || token.isEmpty()) {
      return;
    }
    // 异常长输出只保留一次压缩所需的有限正文，流仍消费至终态或绝对截止时间。
    int available = maxChars * 8 - raw.length();
    if (available <= 0) {
      return;
    }
    raw.append(token, 0, safeEnd(token, Math.min(token.length(), available)));
    String normalized = repairSource();
    int questionEnd = firstQuestionEnd(normalized);
    if (questionEnd > 0 && questionEnd <= maxChars) {
      content = normalized.substring(0, questionEnd);
      emitCompleteSentences();
    }
  }

  String preview() {
    if (!content.isEmpty() || finished) {
      return content;
    }
    String draft = repairSource();
    return draft.substring(0, safeEnd(draft, Math.min(draft.length(), maxChars)));
  }

  String finish() {
    finished = true;
    return content;
  }

  String repairSource() {
    return raw.toString().replace("**", "")
        .replace("```", "")
        .replace("`", "")
        .replaceAll("(?m)^\\s*[-*+]\\s*", "")
        .replaceAll("\\s+", " ").trim();
  }

  boolean hasEmittedSentence() {
    return emittedSentenceCount > 0;
  }

  private void emitCompleteSentences() {
    if (onSentence == null) {
      return;
    }
    int start = 0;
    for (int index = 0; index < content.length(); index++) {
      char value = content.charAt(index);
      if (TERMINAL_PUNCTUATION.indexOf(value) < 0
          || value == '.' && index > 0 && index + 1 < content.length()
          && isIdentifierChar(content.charAt(index - 1))
          && isIdentifierChar(content.charAt(index + 1))) {
        continue;
      }
      int end = index + 1;
      while (end < content.length() && TERMINAL_PUNCTUATION.indexOf(content.charAt(end)) >= 0) {
        end++;
      }
      // 回调可能提交 TTS 后抛错；先记已下发，禁止整体降级重播。
      emittedSentenceCount++;
      onSentence.accept(content.substring(start, end));
      start = end;
      index = end - 1;
    }
  }

  private static int firstQuestionEnd(String text) {
    for (int index = 0; index < text.length(); index++) {
      if (text.charAt(index) == '？' || text.charAt(index) == '?') {
        return index + 1;
      }
    }
    return -1;
  }

  private static boolean isIdentifierChar(char value) {
    return value >= '0' && value <= '9' || value >= 'a' && value <= 'z'
        || value >= 'A' && value <= 'Z' || value == '_';
  }

  private static int safeEnd(String value, int end) {
    if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
        && Character.isLowSurrogate(value.charAt(end))) {
      return end - 1;
    }
    return end;
  }
}
