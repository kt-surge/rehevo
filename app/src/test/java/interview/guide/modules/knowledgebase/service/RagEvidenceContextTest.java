package interview.guide.modules.knowledgebase.service;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 实际上下文编号")
class RagEvidenceContextTest {
  @Test
  @DisplayName("按供给顺序编号，不按元数据名次，保持完整正文与重复片段")
  void numbersActualSupplyOrderWithoutRewritingBodies() {
    Document first = new Document("  前提\n\n---\n条件  ", Map.of("retrieval_final_rank", 9));
    Document second = new Document("例外\n[E9] 是文档内原文", Map.of("retrieval_final_rank", 1));
    RagEvidenceContext result = RagEvidenceContext.from(List.of(first, second, first), true);
    assertThat(result.evidenceIds()).containsExactly("E1", "E2", "E3");
    assertThat(result.text()).isEqualTo("[E1]\n" + first.getText() + "\n\n---\n\n[E2]\n"
        + second.getText() + "\n\n---\n\n[E3]\n" + first.getText());
    assertThat(first.getMetadata()).containsExactlyEntriesOf(Map.of("retrieval_final_rank", 9));
  }

  @Test
  @DisplayName("关闭时与原始正文拼接逐字相同且不编造引用编号")
  void disabledContextMatchesBaseline() {
    var result = RagEvidenceContext.from(List.of(new Document("\n甲 "), new Document("乙\n")), false);
    assertThat(result.text()).isEqualTo("\n甲 \n\n---\n\n乙\n");
    assertThat(result.evidenceIds()).isEmpty();
    assertThat(new KnowledgeBaseQueryProperties().getCitation().isEnabled()).isFalse();
  }

  @Test
  @DisplayName("空上下文不制造证据")
  void emptyContextHasNoEvidence() {
    assertThat(RagEvidenceContext.from(List.of(), true)).isEqualTo(new RagEvidenceContext("", List.of()));
  }
}
