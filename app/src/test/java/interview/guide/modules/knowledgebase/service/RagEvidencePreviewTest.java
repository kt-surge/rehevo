package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("引用预览保持有限预算与原文片段")
class RagEvidencePreviewTest {
  @Test
  @DisplayName("长正文的远处技术条件进入有限预览，不返回完整正文")
  void showsDistantConditionsWithinOriginalBudget() {
    String text = "开头".repeat(200) + " NOACK does not add new messages to the PEL. "
        + "填充".repeat(200) + " CLAIM does not apply NOACK to retrieved pending entries. " + "末尾".repeat(200);
    String preview = RagEvidencePreview.from(text, "NOACK 与 CLAIM？", 240);
    assertThat(preview).contains("NOACK does not add", "CLAIM does not apply");
    assertThat(preview.length()).isLessThanOrEqualTo(240);
  }

  @Test
  @DisplayName("缺少关键词时保持有限前缀，短正文逐字保留")
  void fallsBackAndPreservesShortBody() {
    assertThat(RagEvidencePreview.from("x".repeat(500), "问题", 240)).hasSize(240);
    assertThat(RagEvidencePreview.from(" 原文\n条件 ", "NOACK", 240)).isEqualTo(" 原文\n条件 ");
  }
}
