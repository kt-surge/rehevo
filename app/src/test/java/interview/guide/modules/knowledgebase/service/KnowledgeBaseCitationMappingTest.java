package interview.guide.modules.knowledgebase.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.common.metrics.ApplicationMetrics;
import interview.guide.modules.knowledgebase.model.AnswerEvaluationResponse;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.QueryResponse;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("RAG 同步与流式共享证据供给映射")
class KnowledgeBaseCitationMappingTest {
  @Test
  @DisplayName("响应预览与评测完整正文使用相同编号并关联实际文档")
  @SuppressWarnings("unchecked")
  void evidencePreviewAndFullContextShareSourceIdentity() throws Exception {
    KnowledgeBaseRepository repository = mock(KnowledgeBaseRepository.class);
    KnowledgeBaseEntity kb = new KnowledgeBaseEntity();
    kb.setId(3L);
    kb.setFileHash("frozen-file-sha");
    kb.setOriginalFilename("public-source.md");
    when(repository.findAllById(any())).thenReturn(List.of(kb));
    KnowledgeBaseQueryProperties properties = new KnowledgeBaseQueryProperties();
    properties.getCitation().setEnabled(true);
    var service = service(repository, properties);
    Document doc = Document.builder().id("doc-id").text("条件\n完整正文")
        .metadata(Map.of("kb_id", 3L, "chunk_index", 7, "retrieval_final_rank", 9)).build();
    var context = RagEvidenceContext.from(List.of(doc), true);
    var previewMethod = KnowledgeBaseQueryService.class.getDeclaredMethod(
        "buildEvidence", List.class, Map.class, List.class);
    previewMethod.setAccessible(true);
    var preview = (List<QueryResponse.RetrievalEvidence>) previewMethod.invoke(
        service, List.of(doc), Map.of(3L, kb), context.evidenceIds());
    var fullMethod = KnowledgeBaseQueryService.class.getDeclaredMethod(
        "buildEvaluationEvidence", List.class, List.class);
    fullMethod.setAccessible(true);
    var full = (List<AnswerEvaluationResponse.RetrievalEvidence>) fullMethod.invoke(
        service, List.of(doc), context.evidenceIds());
    assertThat(preview.getFirst().evidenceId()).isEqualTo("E1");
    assertThat(full.getFirst().evidenceId()).isEqualTo("E1");
    assertThat(preview.getFirst().finalRank()).isEqualTo(9);
    assertThat(preview.getFirst().documentSha256()).isEqualTo("frozen-file-sha");
    assertThat(preview.getFirst().vectorDocumentId()).isEqualTo("doc-id");
    assertThat(full.getFirst().content()).isEqualTo(doc.getText());
  }

  @Test
  @DisplayName("实验关闭时系统提示词保持原样，开启后才追加引用约束")
  void citationConstraintOnlyAppearsWhenEnabled() throws Exception {
    var method = KnowledgeBaseQueryService.class.getDeclaredMethod("buildSystemPrompt");
    method.setAccessible(true);
    var disabled = new KnowledgeBaseQueryProperties();
    var enabled = new KnowledgeBaseQueryProperties();
    enabled.getCitation().setEnabled(true);
    String baseline = (String) method.invoke(service(mock(KnowledgeBaseRepository.class), disabled));
    String candidate = (String) method.invoke(service(mock(KnowledgeBaseRepository.class), enabled));
    assertThat(baseline).doesNotContain("# 本轮证据引用");
    assertThat(candidate).contains("# 本轮证据引用", "不使用历史回答中的编号");
  }

  private KnowledgeBaseQueryService service(KnowledgeBaseRepository repository,
      KnowledgeBaseQueryProperties properties) throws Exception {
    return new KnowledgeBaseQueryService(mock(LlmProviderRegistry.class), mock(HybridRetrievalService.class),
        mock(KnowledgeBaseListService.class), mock(KnowledgeBaseCountService.class), repository,
        mock(ApplicationMetrics.class), mock(EvidenceSufficiencyService.class),
        mock(RagRoutingDecisionService.class), properties, new DocumentChunkingProperties(),
        new DefaultResourceLoader());
  }
}
