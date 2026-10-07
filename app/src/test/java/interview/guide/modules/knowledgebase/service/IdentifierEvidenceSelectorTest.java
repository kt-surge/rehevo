package interview.guide.modules.knowledgebase.service;

import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("技术标识正文选择的边界与调用门")
class IdentifierEvidenceSelectorTest {
  @Test
  @DisplayName("正文标题与签名优先于摘要，两个方法的整块证据都保留")
  void reservesDetailsBeforeSummary() {
    List<Document> candidates = List.of(doc("summary", "cancel (boolean flag)\nStops work.\nfinish ()"),
        doc("unrelated", "Other result"),
        doc("cancel-body", "cancel\npublic\nboolean\ncancel\n(boolean flag)\nThe flag has no effect."),
        doc("finish-body", "finish\n<T>\nT\nfinish\n()\nReturns successful result."));
    var selection = IdentifierEvidenceSelector.select("Work.cancel 和 Work.finish 有什么差别？",
        candidates, 4, 200, String::length);
    assertThat(selection.documents()).extracting(Document::getId)
        .containsExactly("cancel-body", "finish-body", "summary", "unrelated");
    assertThat(selection.reservations()).extracting(IdentifierEvidenceSelector.Reservation::detailHeading)
        .containsExactly(true, true);
    assertThat(selection.documents().getFirst().getText()).isEqualTo(candidates.get(2).getText());
  }

  @Test
  @DisplayName("使用 ASCII 标识边界，cancel 不会匹配 isCancelled")
  void exactBoundaryPreventsSubstringMatch() {
    var selection = IdentifierEvidenceSelector.select("Work.cancel 的行为是什么？",
        List.of(doc("substring", "isCancelled returns state"), doc("exact", "cancel is described here")),
        2, 200, String::length);
    assertThat(selection.documents()).extracting(Document::getId).containsExactly("exact", "substring");
    assertThat(selection.reservations()).extracting(IdentifierEvidenceSelector.Reservation::chunkId)
        .containsExactly("exact");
  }

  @Test
  @DisplayName("超预算正文不截断且不挤占后续可装入块，不把预留当实际已选")
  void skipsOversizedReservedChunk() {
    var selection = IdentifierEvidenceSelector.select("Work.cancel 能停止吗？",
        List.of(doc("large", "cancel\npublic boolean cancel()\n" + "x".repeat(200)),
            doc("small", "small fact")), 2, 20, String::length);
    assertThat(selection.documents()).extracting(Document::getId).containsExactly("small");
    assertThat(selection.tokenEstimate()).isEqualTo(10);
    assertThat(selection.reservations()).hasSize(1);
  }

  @Test
  @DisplayName("没有标识或标识过多时只按原顺序选块，最多预留半数位置")
  void limitsAnchorsAndReservationCapacity() {
    assertThat(IdentifierEvidenceSelector.anchors("中文问题")).isEmpty();
    assertThat(IdentifierEvidenceSelector.anchors("one two three four five six seven eight nine")).isEmpty();
    var candidates = List.of(doc("a", "alpha"), doc("b", "beta"), doc("c", "gamma"));
    var selection = IdentifierEvidenceSelector.select("alpha beta gamma", candidates, 2, 20, String::length);
    assertThat(selection.reservations()).hasSize(1);
    var single = IdentifierEvidenceSelector.select("gamma", candidates, 1, 20, String::length);
    assertThat(single.documents()).extracting(Document::getId).containsExactly("a");
    assertThat(single.reservations()).isEmpty();
  }

  @Test
  @DisplayName("实验模式只执行原问题一次向量与词法检索，候选和知识库范围不变")
  void serviceUsesOneOriginalSearch() {
    var vectors = mock(KnowledgeBaseVectorService.class);
    var repository = mock(VectorRepository.class);
    var rerank = mock(QwenRerankService.class);
    var service = new HybridRetrievalService(vectors, repository, rerank,
        new KnowledgeBaseQueryProperties(), new ApplicationMetrics(null));
    String question = "Work.cancel 的中断标志有什么作用？";
    var docs = List.of(doc("summary", "cancel (boolean flag)"),
        doc("body", "cancel\npublic boolean cancel(boolean flag)\nNo interrupt guarantee."));
    when(vectors.similaritySearch(question, List.of(17L, 29L), 20, 0.28)).thenReturn(docs);
    when(repository.lexicalSearch(question, List.of(17L, 29L), 20)).thenReturn(List.of());
    var trace = service.retrieveWithTrace(question, question, List.of(17L, 29L), 2, 0.28,
        RetrievalMode.HYBRID_IDENTIFIER, 100);
    assertThat(trace.candidates()).extracting(Document::getId).containsExactly("summary", "body");
    assertThat(trace.documents()).extracting(Document::getId).containsExactly("body", "summary");
    assertThat(trace.vectorSearchCalls()).isEqualTo(1);
    assertThat(trace.focusedQueries()).isEmpty();
    assertThat(trace.identifierReservations()).extracting(IdentifierEvidenceSelector.Reservation::chunkId)
        .containsExactly("body");
    verify(vectors).similaritySearch(question, List.of(17L, 29L), 20, 0.28);
    verify(repository).lexicalSearch(question, List.of(17L, 29L), 20);
    verifyNoMoreInteractions(vectors, repository);
    verifyNoInteractions(rerank);
  }

  @Test
  @DisplayName("默认 HYBRID 保留原上下文，没有正文预留和强制预算")
  void defaultPathRemainsOriginal() {
    var vectors = mock(KnowledgeBaseVectorService.class);
    var repository = mock(VectorRepository.class);
    var service = new HybridRetrievalService(vectors, repository, mock(QwenRerankService.class),
        new KnowledgeBaseQueryProperties(), new ApplicationMetrics(null));
    String question = "Work.cancel 的语义是什么？";
    var docs = List.of(doc("summary", "cancel (boolean flag)"),
        doc("body", "cancel\npublic boolean cancel(boolean flag)\nNo interrupt guarantee."));
    when(vectors.similaritySearch(question, List.of(17L), 20, 0.28)).thenReturn(docs);
    when(repository.lexicalSearch(question, List.of(17L), 20)).thenReturn(List.of());
    var trace = service.retrieveWithTrace(question, question, List.of(17L), 1, 0.28, RetrievalMode.HYBRID);
    assertThat(trace.documents()).extracting(Document::getId).containsExactly("summary");
    assertThat(trace.identifierReservations()).isEmpty();
    assertThat(trace.contextTokenBudget()).isNull();
    assertThat(trace.contextTokenEstimate()).isNull();
  }

  private Document doc(String id, String text) {
    return Document.builder().id(id).text(text).score(0.8).build();
  }

  @Test
  @DisplayName("连续同名标题不丢失第二个标题，签名后的空白不受扫描窗口截断")
  void adjacentHeadingAndUnboundedSignatureWhitespace() {
    String text = "cancel\ncancel\n" + "x".repeat(299) + " cancel" + "\n".repeat(500) + "(flag)";
    var selection = IdentifierEvidenceSelector.select("Work.cancel",
        List.of(doc("summary", "cancel(flag)"), doc("detail", text)), 2, 2000, String::length);
    assertThat(selection.reservations()).extracting(IdentifierEvidenceSelector.Reservation::chunkId)
        .containsExactly("detail");
    assertThat(selection.reservations().getFirst().detailHeading()).isTrue();
  }

  @Test
  @DisplayName("旧正则的 120 加 180 扫描边界按码点计算，超过边界不预留正文")
  void scanWindowCountsCodePointsAndKeepsInclusiveBoundary() {
    for (String unit : List.of("x", "😀")) {
      for (int distance : List.of(300, 301)) {
        String text = "cancel\n" + unit.repeat(distance - 1) + " cancel()";
        var selection = IdentifierEvidenceSelector.select("Work.cancel",
            List.of(doc("summary", "cancel(flag)"), doc("detail", text)), 2, 2000, String::length);
        assertThat(selection.reservations().getFirst().detailHeading())
            .as("unit=%s, distance=%s", unit, distance).isEqualTo(distance == 300);
      }
    }
  }

  @Test
  @DisplayName("CRLF 和默认正则空白仍匹配，Unicode 非默认空白不能被误当签名空白")
  void regexWhitespaceSemanticsArePreserved() {
    for (String whitespace : List.of("\r\n\t\f\u000b ", "\u00a0")) {
      var selection = IdentifierEvidenceSelector.select("Work.cancel",
          List.of(doc("summary", "cancel(flag)"),
              doc("detail", "cancel\r\npublic cancel" + whitespace + "()")),
          2, 2000, String::length);
      assertThat(selection.reservations().getFirst().detailHeading())
          .isEqualTo(!whitespace.equals("\u00a0"));
    }
  }
}
