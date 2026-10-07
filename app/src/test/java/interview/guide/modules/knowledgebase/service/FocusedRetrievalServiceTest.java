package interview.guide.modules.knowledgebase.service;

import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("分题召回的调用、范围与预算门")
class FocusedRetrievalServiceTest {
  private final KnowledgeBaseVectorService vectors = mock(KnowledgeBaseVectorService.class);
  private final VectorRepository repository = mock(VectorRepository.class);
  private final QwenRerankService rerank = mock(QwenRerankService.class);
  private final HybridRetrievalService service = new HybridRetrievalService(vectors, repository,
      rerank, new KnowledgeBaseQueryProperties(), new ApplicationMetrics(null));

  @Test
  @DisplayName("比较问题最多三次检索，所有支路沿用同一知识库范围，记录各自候选")
  void capsSearchesAndKeepsScope() {
    String question = "等待全部结果时，CompletableFuture.allOf 和 ExecutorService.invokeAll 的结果形式有什么区别？";
    stub(question, List.of(doc("a", "first result"), doc("b", "second result")));
    stub("CompletableFuture.allOf", List.of(doc("a", "first result"), doc("c", "third result")));
    stub("ExecutorService.invokeAll", List.of(doc("d", "fourth result"), doc("b", "second result")));
    var trace = service.retrieveWithTrace(question, question, List.of(9L), 3, 0.28,
        RetrievalMode.HYBRID_FOCUSED, 30);
    assertThat(trace.vectorSearchCalls()).isEqualTo(3);
    assertThat(trace.focusedQueries()).extracting(HybridRetrievalService.FocusedQueryTrace::query)
        .containsExactly("CompletableFuture.allOf", "ExecutorService.invokeAll");
    assertThat(trace.documents()).extracting(Document::getId).containsExactly("a", "d", "b");
    assertThat(trace.candidates()).extracting(Document::getId).doesNotHaveDuplicates();
    assertThat(trace.contextTokenEstimate()).isLessThanOrEqualTo(30);
    for (String query : List.of(question, "CompletableFuture.allOf", "ExecutorService.invokeAll")) {
      verify(vectors).similaritySearch(query, List.of(9L), 20, 0.28);
      verify(repository).lexicalSearch(query, List.of(9L), 20);
    }
    verifyNoMoreInteractions(vectors, repository);
    verifyNoInteractions(rerank);
  }

  @Test
  @DisplayName("预算装入整块且跳过超大块，不截断内容")
  void budgetSkipsLargeWholeChunk() {
    String question = "查询一个普通主题";
    stub(question, List.of(doc("large", "large content ".repeat(100)), doc("small", "useful fact")));
    var trace = service.retrieveWithTrace(question, question, List.of(9L), 2, 0.28,
        RetrievalMode.HYBRID, 10);
    assertThat(trace.documents()).extracting(Document::getId).containsExactly("small");
    assertThat(trace.documents().getFirst().getText()).isEqualTo("useful fact");
    assertThat(trace.contextTokenEstimate()).isEqualTo(new JTokkitTokenCountEstimator().estimate("useful fact"));
  }

  @Test
  @DisplayName("单主题候选仅原检索一次，未指定预算时不超过原上下文")
  void oneSearchFallbackUsesOriginalBudget() {
    String question = "CompletableFuture.cancel(true) 到底能不能停止后台异步工作？";
    stub(question, List.of(doc("a", "known fact"), doc("b", "another fact")));
    var trace = service.retrieveWithTrace(question, question, List.of(9L), 1, 0.28,
        RetrievalMode.HYBRID_FOCUSED);
    assertThat(trace.vectorSearchCalls()).isEqualTo(1);
    assertThat(trace.focusedQueries()).isEmpty();
    assertThat(trace.documents()).extracting(Document::getId).containsExactly("a");
    assertThat(trace.contextTokenEstimate()).isLessThanOrEqualTo(trace.contextTokenBudget());
    verify(vectors).similaritySearch(question, List.of(9L), 20, 0.28);
    verifyNoMoreInteractions(vectors);
  }

  private void stub(String query, List<Document> documents) {
    when(vectors.similaritySearch(query, List.of(9L), 20, 0.28)).thenReturn(documents);
    when(repository.lexicalSearch(query, List.of(9L), 20)).thenReturn(List.of());
  }

  private Document doc(String id, String text) {
    return Document.builder().id(id).text(text).score(0.8).build();
  }
}
