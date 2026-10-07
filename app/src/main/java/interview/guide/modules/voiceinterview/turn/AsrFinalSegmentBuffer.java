package interview.guide.modules.voiceinterview.turn;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-session ASR final-segment buffer.
 *
 * <p>Streaming ASR providers may repeat a completed segment or later emit an
 * extended version of it. Known sentence identities distinguish repetition from
 * duplicate delivery. Older providers without identities retain their cumulative
 * text behavior. Partial text is displayed without changing the next LLM input.</p>
 */
public final class AsrFinalSegmentBuffer {

  private final Map<SegmentKey, String> confirmed = new LinkedHashMap<>();
  private long startedAtMillis;

  public synchronized void appendFinal(String segment, long nowMillis) {
    appendFinal(AsrTranscriptSegment.unidentified(segment), nowMillis);
  }

  public synchronized void appendFinal(AsrTranscriptSegment segment, long nowMillis) {
    String normalized = normalize(segment.text());
    if (normalized.isEmpty()) {
      return;
    }
    if (confirmed.isEmpty()) {
      startedAtMillis = nowMillis;
    }
    SegmentKey key = key(segment);
    String previous = confirmed.get(key);
    confirmed.put(key, segment.sentenceId() == null && previous != null
      ? join(previous, normalized) : normalized);
  }

  public synchronized void replaceWithFinal(String text, long nowMillis) {
    String normalized = normalize(text);
    if (normalized.isEmpty()) {
      return;
    }
    confirmed.clear();
    confirmed.put(new SegmentKey(null, null), normalized);
    if (startedAtMillis == 0) {
      startedAtMillis = nowMillis;
    }
  }

  public synchronized String preview() {
    return render(confirmed);
  }

  public synchronized String previewWithPartial(String partial) {
    return previewWithPartial(AsrTranscriptSegment.unidentified(partial));
  }

  public synchronized String previewWithPartial(AsrTranscriptSegment partial) {
    String normalized = normalize(partial.text());
    if (normalized.isEmpty()) {
      return preview();
    }
    Map<SegmentKey, String> display = new LinkedHashMap<>(confirmed);
    SegmentKey key = key(partial);
    if (partial.sentenceId() != null && confirmed.containsKey(key)) {
      return preview();
    }
    String previous = display.get(key);
    display.put(key, partial.sentenceId() == null && previous != null
      ? join(previous, normalized) : normalized);
    return render(display);
  }

  public synchronized String takeAndClear() {
    String result = preview();
    confirmed.clear();
    startedAtMillis = 0;
    return result;
  }

  public synchronized long startedAtOr(long fallbackMillis) {
    return startedAtMillis > 0 ? startedAtMillis : fallbackMillis;
  }

  private static String join(String previous, String next) {
    if (next.equals(previous) || next.startsWith(previous)) {
      return next;
    }
    if (previous.endsWith(next)) {
      return previous;
    }
    return concatenate(previous, next);
  }

  private static String render(Map<SegmentKey, String> segments) {
    String result = "";
    for (String text : segments.values()) {
      result = result.isEmpty() ? text : concatenate(result, text);
    }
    return result;
  }

  private static String concatenate(String previous, String next) {
    return previous + (endsSentence(previous) ? " " : "，") + next;
  }

  private static SegmentKey key(AsrTranscriptSegment segment) {
    return new SegmentKey(segment.recognitionId(), segment.sentenceId());
  }

  private static boolean endsSentence(String text) {
    return text.endsWith("。") || text.endsWith("！") || text.endsWith("？")
      || text.endsWith(".") || text.endsWith("!") || text.endsWith("?");
  }

  private static String normalize(String text) {
    return text == null ? "" : text.trim();
  }

  private record SegmentKey(String recognitionId, Long sentenceId) { }
}
