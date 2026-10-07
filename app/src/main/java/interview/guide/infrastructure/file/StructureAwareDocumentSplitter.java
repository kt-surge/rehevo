package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 保留原文片段与边界的有界切块。重复的代码围栏/表头必须来自原文，
 * 每段文本记录 UTF-16 映射；不生成摘要或补写事实。Token 为本地统一估算。
 */
public class StructureAwareDocumentSplitter {
  public static final String VERSION = "structured-source-spans-v1";
  private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}[ \\t]+.+");
  private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,}).*");
  private static final Pattern TABLE_SEPARATOR = Pattern.compile(
      "^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");
  private final JTokkitTokenCountEstimator estimator = new JTokkitTokenCountEstimator();

  public List<Document> split(String source, int maxTokens) {
    if (source == null || source.isBlank()) {
      return List.of();
    }
    if (maxTokens < 64 || maxTokens > 2048) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "结构切块预算必须在 64 到 2048 之间");
    }
    List<Document> result = new ArrayList<>();
    List<Block> packed = new ArrayList<>();
    for (Block block : blocks(source)) {
      if (block.kind().equals("heading") && !packed.isEmpty()) {
        flush(source, packed, result);
      }
      if (tokens(source.substring(block.start(), block.end())) > maxTokens) {
        flush(source, packed, result);
        fragment(source, block, maxTokens, result);
      } else {
        if (!packed.isEmpty() && tokens(source.substring(packed.getFirst().start(), block.end())) > maxTokens) {
          flush(source, packed, result);
        }
        packed.add(block);
      }
    }
    flush(source, packed, result);
    return result;
  }

  private List<Block> blocks(String source) {
    List<Line> lines = new ArrayList<>();
    int cursor = 0;
    while (cursor < source.length()) {
      int newline = source.indexOf('\n', cursor);
      int end = newline < 0 ? source.length() : newline + 1;
      lines.add(new Line(cursor, end, source.substring(cursor, end).stripTrailing()));
      cursor = end;
    }
    List<Block> result = new ArrayList<>();
    String heading = "";
    for (int index = 0; index < lines.size();) {
      Line first = lines.get(index);
      var fence = FENCE.matcher(first.text());
      if (fence.matches()) {
        String marker = fence.group(1);
        Pattern closing = Pattern.compile("^ {0,3}" + marker.charAt(0) + "{" + marker.length() + ",}\\s*$");
        int last = index + 1;
        while (last < lines.size() && !closing.matcher(lines.get(last).text()).matches()) {
          last++;
        }
        boolean closed = last < lines.size();
        int end = closed ? lines.get(last).end() : source.length();
        int bodyEnd = closed ? lines.get(last).start() : end;
        result.add(new Block(first.start(), end, "code", heading, first.end(), bodyEnd, closed));
        index = closed ? last + 1 : lines.size();
      } else if (HEADING.matcher(first.text()).matches()) {
        heading = first.text().strip();
        result.add(new Block(first.start(), first.end(), "heading", heading, first.start(), first.end(), false));
        index++;
      } else if (index + 1 < lines.size() && first.text().contains("|")
          && TABLE_SEPARATOR.matcher(lines.get(index + 1).text()).matches()) {
        int last = index + 2;
        while (last < lines.size() && !lines.get(last).text().isBlank() && lines.get(last).text().contains("|")) {
          last++;
        }
        int end = lines.get(last - 1).end();
        result.add(new Block(first.start(), end, "table", heading, lines.get(index + 1).end(), end, false));
        index = last;
      } else {
        int last = index + 1;
        // 空白行独立保留；段落扫描不能跨越后续标题、围栏或表格。
        while (!first.text().isBlank() && last < lines.size()) {
          Line next = lines.get(last);
          if (next.text().isBlank() || HEADING.matcher(next.text()).matches() || FENCE.matcher(next.text()).matches()
              || (last + 1 < lines.size() && next.text().contains("|")
                  && TABLE_SEPARATOR.matcher(lines.get(last + 1).text()).matches())) {
            break;
          }
          last++;
        }
        int end = lines.get(last - 1).end();
        result.add(new Block(first.start(), end, "prose", heading, first.start(), end, false));
        index = last;
      }
    }
    return result;
  }

  private void flush(String source, List<Block> packed, List<Document> result) {
    if (packed.isEmpty()) {
      return;
    }
    int start = packed.getFirst().start();
    int end = packed.getLast().end();
    if (!source.substring(start, end).isBlank()) {
      Block grouped = packed.size() == 1 ? packed.getFirst()
          : new Block(start, end, "mixed", packed.getFirst().heading(), start, end, false);
      Document document = document(source, List.of(new Range(start, end)), grouped, false, false);
      document.getMetadata().put("chunk_unclosed_fence", packed.stream()
          .anyMatch(block -> block.kind().equals("code") && !block.closed()));
      result.add(document);
    }
    packed.clear();
  }

  private void fragment(String source, Block block, int maxTokens, List<Document> result) {
    boolean wrapped = block.kind().equals("code") || block.kind().equals("table");
    int bodyStart = wrapped ? block.bodyStart() : block.start();
    int bodyEnd = wrapped ? block.bodyEnd() : block.end();
    List<Range> prefix = wrapped ? List.of(new Range(block.start(), bodyStart)) : List.of();
    int closingStart = bodyEnd > bodyStart && source.charAt(bodyEnd - 1) == '\n' ? bodyEnd - 1 : bodyEnd;
    List<Range> suffix = wrapped && block.closed()
        ? List.of(new Range(closingStart, block.end())) : List.of();
    String prefixText = text(source, prefix);
    String suffixText = text(source, suffix);
    // 表头/围栏本身超预算时不伪造缩写，退回有位置映射的硬片段。
    if (tokens(prefixText + suffixText) >= maxTokens || bodyStart == bodyEnd) {
      wrapped = false;
      bodyStart = block.start();
      bodyEnd = block.end();
      prefix = List.of();
      suffix = List.of();
      prefixText = "";
      suffixText = "";
    }
    while (bodyStart < bodyEnd) {
      int end = boundedEnd(source, bodyStart, bodyEnd, prefixText, suffixText, maxTokens);
      // 优先完整行；只有超过预算的单行才会在 Unicode 码点边界拆开。
      int newline = source.lastIndexOf('\n', end - 1);
      if (newline >= bodyStart && newline + 1 >= bodyStart + (end - bodyStart) / 2) {
        end = newline + 1;
      }
      List<Range> ranges = new ArrayList<>(prefix);
      ranges.add(new Range(bodyStart, end));
      ranges.addAll(suffix);
      Document document = document(source, ranges, block, true, wrapped);
      document.getMetadata().put("chunk_split_inside_line",
          (bodyStart > block.bodyStart() && source.charAt(bodyStart - 1) != '\n')
              || (end < bodyEnd && source.charAt(end - 1) != '\n'));
      if (tokens(document.getText()) > maxTokens) {
        throw new BusinessException(ErrorCode.INTERNAL_ERROR, "结构切块超过已校验的 Token 预算");
      }
      result.add(document);
      bodyStart = end;
    }
  }

  private int boundedEnd(String source, int start, int end, String prefix, String suffix, int maxTokens) {
    int low = 0;
    int high = source.codePointCount(start, end);
    while (low < high) {
      int middle = (low + high + 1) / 2;
      int candidate = source.offsetByCodePoints(start, middle);
      if (tokens(prefix + source.substring(start, candidate) + suffix) <= maxTokens) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    if (low == 0) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "结构切块预算无法容纳一个完整 Unicode 字符");
    }
    return source.offsetByCodePoints(start, low);
  }

  private Document document(String source, List<Range> ranges, Block block, boolean fragmented, boolean wrapped) {
    List<Map<String, Integer>> spans = new ArrayList<>();
    int position = 0;
    for (Range range : ranges) {
      if (range.start() == range.end()) {
        continue;
      }
      int length = range.end() - range.start();
      spans.add(Map.of("source_start", range.start(), "source_end", range.end(),
          "chunk_start", position, "chunk_end", position + length));
      position += length;
    }
    String content = text(source, ranges);
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("chunking_version", VERSION);
    metadata.put("chunking_mode", "STRUCTURED");
    metadata.put("source_offset_unit", "UTF16");
    metadata.put("source_spans", spans);
    metadata.put("source_block_start", block.start());
    metadata.put("source_block_end", block.end());
    metadata.put("source_block_kind", block.kind());
    metadata.put("source_heading", block.heading());
    metadata.put("chunk_fragmented", fragmented);
    metadata.put("chunk_header_repeated", wrapped);
    metadata.put("chunk_unclosed_fence", block.kind().equals("code") && !block.closed());
    metadata.put("chunk_token_estimate", tokens(content));
    return Document.builder().text(content).metadata(metadata).build();
  }

  private String text(String source, List<Range> ranges) {
    StringBuilder text = new StringBuilder();
    ranges.forEach(range -> text.append(source, range.start(), range.end()));
    return text.toString();
  }

  private int tokens(String text) {
    return estimator.estimate(text);
  }

  private record Range(int start, int end) {}
  private record Line(int start, int end, String text) {}
  private record Block(int start, int end, String kind, String heading,
                       int bodyStart, int bodyEnd, boolean closed) {}
}
