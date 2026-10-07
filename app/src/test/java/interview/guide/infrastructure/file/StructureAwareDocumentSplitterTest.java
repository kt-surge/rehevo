package interview.guide.infrastructure.file;

import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("结构切块原文、预算与格式边界")
class StructureAwareDocumentSplitterTest {
  private final StructureAwareDocumentSplitter splitter = new StructureAwareDocumentSplitter();
  private final JTokkitTokenCountEstimator estimator = new JTokkitTokenCountEstimator();

  @Test
  @DisplayName("默认策略保持旧 TokenTextSplitter 的文本与元数据")
  void defaultKeepsLegacySplitter() {
    String source = ("## 标题\n\n中文事实与条件，不能随意省略。\n\n```java\nvoid test() {}\n```\n").repeat(80);
    List<Document> old = TokenTextSplitter.builder().build().apply(List.of(new Document(source)));
    List<Document> actual = new DocumentChunkingService(new DocumentChunkingProperties()).split(source);
    assertThat(actual).extracting(Document::getText).containsExactlyElementsOf(old.stream().map(Document::getText).toList());
    // 两次独立导入的 parent_document_id 本来就不同；其余结构必须完全一致。
    assertThat(actual).extracting(chunk -> withoutParentId(chunk.getMetadata()))
        .containsExactlyElementsOf(old.stream().map(chunk -> withoutParentId(chunk.getMetadata())).toList());
    assertThat(actual.stream().map(chunk -> chunk.getMetadata().get("parent_document_id")).distinct())
        .hasSize(1).allSatisfy(value -> assertThat(value.toString())
            .matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"));
  }

  @Test
  @DisplayName("标题切换不会与上一节打包且保留标题上下文")
  void headingsStartNewChunks() {
    String source = "# 第一节\n\n第一节事实。\n\n# 第二节\n\n第二节条件。";
    List<Document> chunks = splitter.split(source, 800);
    assertThat(chunks).hasSize(2);
    assertThat(chunks.getFirst().getText()).contains("第一节事实").doesNotContain("第二节");
    assertThat(chunks.getLast().getMetadata()).containsEntry("source_heading", "# 第二节");
    verifyMappingAndBudget(source, chunks, 800);
  }

  @Test
  @DisplayName("预算内代码与表格不在行间拆开")
  void keepsSmallCodeAndTable() {
    String source = "# 小节\n\n```java\nString value = \"# 假标题\";\n```\n\n| 名称 | 条件 |\n| --- | --- |\n| A | 不得删除 |\n";
    List<Document> chunks = splitter.split(source, 800);
    assertThat(chunks).hasSize(1);
    assertThat(chunks.getFirst().getText()).isEqualTo(source);
    verifyMappingAndBudget(source, chunks, 800);
  }

  @Test
  @DisplayName("超长代码重复真实围栏并保留每一行原文映射")
  void oversizedCodeRepeatsOnlyOriginalFences() {
    String source = "```java\n" + "String value = \"条件不得省略\";\n".repeat(40) + "```\n";
    List<Document> chunks = splitter.split(source, 64);
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allSatisfy(chunk -> {
      assertThat(chunk.getText()).startsWith("```java\n").endsWith("\n```\n");
      assertThat(chunk.getMetadata()).containsEntry("chunk_header_repeated", true);
    });
    verifyMappingAndBudget(source, chunks, 64);
    verifyEverySourceCharacterCovered(source, chunks);
  }

  @Test
  @DisplayName("超长表格每块保留真实列名且不丢失数据行")
  void oversizedTableKeepsHeaders() {
    String header = "| 名称 | 必要条件 |\n| --- | --- |\n";
    String source = header + "| record | 不得在事务内调用外部服务 |\n".repeat(30);
    List<Document> chunks = splitter.split(source, 64);
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getText()).startsWith(header));
    verifyMappingAndBudget(source, chunks, 64);
    verifyEverySourceCharacterCovered(source, chunks);
  }

  @Test
  @DisplayName("超长单行按码点切分，不拆中文或 emoji 代理对")
  void longLinePreservesUnicode() {
    String source = "```text\n" + "中文😀e\u0301技术标识".repeat(80) + "\n```\n";
    List<Document> chunks = splitter.split(source, 64);
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getText()).doesNotContain("\uFFFD"));
    verifyMappingAndBudget(source, chunks, 64);
    verifyEverySourceCharacterCovered(source, chunks);
  }

  @Test
  @DisplayName("未闭合围栏保留原文而不补写闭合标记或解释内部标题")
  void unterminatedFenceStaysExplicit() {
    String source = "```text\n" + "# 这是代码内容，不是新小节\n".repeat(20);
    List<Document> chunks = splitter.split(source, 64);
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allSatisfy(chunk -> {
      assertThat(chunk.getMetadata()).containsEntry("chunk_unclosed_fence", true)
          .containsEntry("source_heading", "");
      assertThat(chunk.getText()).startsWith("```text\n");
    });
    verifyMappingAndBudget(source, chunks, 64);
    verifyEverySourceCharacterCovered(source, chunks);
  }

  @Test
  @DisplayName("围栏自身超预算时不无限循环且显式退回原文片段")
  void oversizedFenceHeaderCannotCauseInfiniteLoop() {
    String source = "```" + "language ".repeat(100) + "\nbody\n```\n";
    List<Document> chunks = splitter.split(source, 64);
    assertThat(chunks).hasSizeGreaterThan(1);
    assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getMetadata())
        .containsEntry("chunk_header_repeated", false).containsEntry("chunk_fragmented", true));
    verifyMappingAndBudget(source, chunks, 64);
    verifyEverySourceCharacterCovered(source, chunks);
  }

  @Test
  @DisplayName("空文档无虚构片段，错误预算使用业务异常")
  void validatesEmptyAndBudget() {
    assertThat(splitter.split("\n ", 800)).isEmpty();
    assertThatThrownBy(() -> splitter.split("事实", 20)).isInstanceOf(BusinessException.class);
  }

  @SuppressWarnings("unchecked")
  private void verifyMappingAndBudget(String source, List<Document> chunks, int maxTokens) {
    for (Document chunk : chunks) {
      assertThat(estimator.estimate(chunk.getText())).isLessThanOrEqualTo(maxTokens);
      List<Map<String, Integer>> spans = (List<Map<String, Integer>>) chunk.getMetadata().get("source_spans");
      int cursor = 0;
      for (Map<String, Integer> span : spans) {
        assertThat(span.get("chunk_start")).isEqualTo(cursor);
        assertThat(source.substring(span.get("source_start"), span.get("source_end")))
            .isEqualTo(chunk.getText().substring(span.get("chunk_start"), span.get("chunk_end")));
        cursor = span.get("chunk_end");
      }
      assertThat(cursor).isEqualTo(chunk.getText().length());
    }
  }

  @SuppressWarnings("unchecked")
  private void verifyEverySourceCharacterCovered(String source, List<Document> chunks) {
    boolean[] covered = new boolean[source.length()];
    for (Document chunk : chunks) {
      List<Map<String, Integer>> spans = (List<Map<String, Integer>>) chunk.getMetadata().get("source_spans");
      for (Map<String, Integer> span : spans) {
        for (int position = span.get("source_start"); position < span.get("source_end"); position++) {
          covered[position] = true;
        }
      }
    }
    for (int position = 0; position < covered.length; position++) {
      assertThat(covered[position]).as("原文位置 %s 必须保留", position).isTrue();
    }
  }

  private Map<String, Object> withoutParentId(Map<String, Object> metadata) {
    Map<String, Object> result = new HashMap<>(metadata);
    result.remove("parent_document_id");
    return result;
  }
}
