package interview.guide.modules.knowledgebase.service;

import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("混合检索 RRF 融合测试")
class HybridRetrievalServiceTest {

  @Test
  @DisplayName("两路同时命中的文档应排在仅单路命中的文档之前")
  void documentHitByBothRankingsComesFirst() {
    List<Document> vector = List.of(document("a"), document("b"), document("c"));
    List<Document> lexical = List.of(document("b"), document("d"), document("a"));

    List<Document> result = HybridRetrievalService.reciprocalRankFusion(vector, lexical, 60, 3.0, 1.0, 4);

    assertThat(result).extracting(Document::getId).containsExactly("a", "b", "c", "d");
    assertThat(result.get(1).getMetadata())
        .containsEntry("retrieval_vector_rank", 2)
        .containsEntry("retrieval_lexical_rank", 1)
        .containsEntry("retrieval_sources", List.of("vector", "lexical"));
  }

  @Test
  @DisplayName("RRF 相同分数使用文档ID稳定排序")
  void equalScoresUseStableDocumentIdOrder() {
    List<Document> result = HybridRetrievalService.reciprocalRankFusion(
        List.of(document("b")), List.of(document("a")), 60, 1.0, 1.0, 2);

    assertThat(result).extracting(Document::getId).containsExactly("a", "b");
  }

  @Test
  @DisplayName("上下文模式在命中段后插入同文档相邻段，并保持候选上限")
  void interleavesAdjacentChunksAfterTopSeeds() {
    Document firstSeed = document("seed-0", 8L, 0);
    Document secondSeed = document("seed-3", 8L, 3);
    Document other = document("other", 9L, 0);
    Document neighborOne = document("neighbor-1", 8L, 1);
    Document neighborTwo = document("neighbor-2", 8L, 2);

    List<Document> result = HybridRetrievalService.interleaveAdjacentChunks(
        List.of(firstSeed, secondSeed, other), List.of(neighborOne, neighborTwo), 2, 2, 5);

    assertThat(result).extracting(Document::getId)
        .containsExactly("seed-0", "neighbor-1", "seed-3", "neighbor-2", "other");
    assertThat(result.get(1).getMetadata())
        .containsEntry("retrieval_sources", List.of("adjacent_context"))
        .containsEntry("retrieval_adjacent_to", "seed-0");
  }

  @Test
  @DisplayName("诊断保留融合后的裁剪损失，单次请求只调用一次向量检索")
  void tracePreservesCandidatesBeforeContextLimit() {
    var vectorService = mock(KnowledgeBaseVectorService.class);
    var repository = mock(VectorRepository.class);
    var rerank = mock(QwenRerankService.class);
    var properties = new KnowledgeBaseQueryProperties();
    var service = new HybridRetrievalService(vectorService, repository, rerank,
        properties, new ApplicationMetrics(null));
    when(vectorService.similaritySearch("query", List.of(8L), 20, 0.28))
        .thenReturn(List.of(document("a"), document("b"), document("c")));
    when(repository.lexicalSearch("query", List.of(8L), 20))
        .thenReturn(List.of(document("b"), document("d")));

    var trace = service.retrieveWithTrace("query", "question", List.of(8L), 2, 0.28,
        RetrievalMode.HYBRID);

    assertThat(trace.candidates()).extracting(Document::getId).containsExactly("b", "a", "c", "d");
    assertThat(trace.documents()).extracting(Document::getId).containsExactly("b", "a");
    assertThat(trace.candidates().getFirst().getMetadata()).doesNotContainKey("retrieval_final_rank");
    assertThat(trace.documents().getFirst().getMetadata()).containsEntry("retrieval_final_rank", 1);
    verify(vectorService).similaritySearch("query", List.of(8L), 20, 0.28);
    verify(repository).lexicalSearch("query", List.of(8L), 20);
    verifyNoInteractions(rerank);
  }

  @Test
  @DisplayName("向量模式诊断不触发词法或重排请求")
  void vectorTraceDoesNotEnableOtherRetrievers() {
    var vectorService = mock(KnowledgeBaseVectorService.class);
    var repository = mock(VectorRepository.class);
    var rerank = mock(QwenRerankService.class);
    var service = new HybridRetrievalService(vectorService, repository, rerank,
        new KnowledgeBaseQueryProperties(), new ApplicationMetrics(null));
    when(vectorService.similaritySearch("query", List.of(8L), 2, 0.28))
        .thenReturn(List.of(document("a"), document("b")));

    var trace = service.retrieveWithTrace("query", "question", List.of(8L), 2, 0.28,
        RetrievalMode.VECTOR);

    assertThat(trace.candidates()).extracting(Document::getId).containsExactly("a", "b");
    assertThat(trace.lexicalDocuments()).isEmpty();
    assertThat(trace.documents()).hasSize(2);
    verify(vectorService).similaritySearch("query", List.of(8L), 2, 0.28);
    verifyNoInteractions(repository, rerank);
  }

  @Test
  @DisplayName("词法失败时诊断保留向量候选与既有降级结果")
  void lexicalFailureRetainsVectorTrace() {
    var vectorService = mock(KnowledgeBaseVectorService.class);
    var repository = mock(VectorRepository.class);
    var service = new HybridRetrievalService(vectorService, repository, mock(QwenRerankService.class),
        new KnowledgeBaseQueryProperties(), new ApplicationMetrics(null));
    when(vectorService.similaritySearch("query", List.of(8L), 20, 0.28))
        .thenReturn(List.of(document("a")));
    when(repository.lexicalSearch("query", List.of(8L), 20))
        .thenThrow(new IllegalStateException("fixture database failure"));

    var trace = service.retrieveWithTrace("query", "question", List.of(8L), 2, 0.28,
        RetrievalMode.HYBRID);

    assertThat(trace.lexicalDocuments()).isEmpty();
    assertThat(trace.candidates()).extracting(Document::getId).containsExactly("a");
    assertThat(trace.documents()).extracting(Document::getId).containsExactly("a");
  }

  private Document document(String id) {
    return Document.builder().id(id).text("content-" + id).metadata(Map.of()).score(0.8).build();
  }

  private Document document(String id, long knowledgeBaseId, int chunkIndex) {
    return Document.builder().id(id).text("content-" + id)
        .metadata(Map.of("kb_id", String.valueOf(knowledgeBaseId), "chunk_index", chunkIndex)).score(0.8).build();
  }
}
