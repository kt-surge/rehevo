package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.CitationValidationReport.Status;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 引用编号存在性，独立于断言支持")
class RagCitationValidatorTest {
  @Test
  @DisplayName("有效编号只说明来源存在，重复引用按出现顺序去重")
  void recordsReferenceOrderWithoutTruthClaim() {
    var report = RagCitationValidator.check("伪造断言。[E2][E1] 再次[E2]", List.of("E1", "E2"));
    assertThat(report.status()).isEqualTo(Status.REFERENCES_KNOWN);
    assertThat(report.referencedEvidenceIds()).containsExactly("E2", "E1");
    assertThat(report.unknownEvidenceIds()).isEmpty();
  }

  @Test
  @DisplayName("没有引用单独返回状态，不能以空集合当作校验通过")
  void noReferencesIsNotKnownReferences() {
    assertThat(RagCitationValidator.check("信息不足。", List.of("E1")).status())
        .isEqualTo(Status.NO_REFERENCES);
  }

  @Test
  @DisplayName("未知与超大编号保留字符串，不溢出或推测最近来源")
  void rejectsUnknownIncludingLargeAndZeroPaddedIdentifiers() {
    var report = RagCitationValidator.check("[E0][E01][E999999999999999999999][E1]", List.of("E1"));
    assertThat(report.status()).isEqualTo(Status.UNKNOWN_REFERENCES);
    assertThat(report.unknownEvidenceIds()).containsExactly("E0", "E01", "E999999999999999999999");
  }

  @Test
  @DisplayName("旧快照或开关关闭不能事后赋予本轮编号")
  void legacySnapshotDisablesValidation() {
    assertThat(RagCitationValidator.check("历史[E1]", Arrays.asList(null, "")).status())
        .isEqualTo(Status.DISABLED);
    assertThat(RagCitationValidator.check("历史[E1]", null).referencedEvidenceIds()).isEmpty();
  }

  @Test
  @DisplayName("行内代码、两种围栏、转义及显式链接不是正文引用")
  void skipsCodeEscapesAndLinks() {
    String answer = "`[E8]` ``代码`[E9]`` \\[E7] [E6](https://example.org) [E5][ref]\n"
        + "```java\n[E4]\n```\n~~~\n[E3]\n~~~\n有效[E1]";
    assertThat(RagCitationValidator.check(answer, List.of("E1")).referencedEvidenceIds())
        .containsExactly("E1");
  }

  @Test
  @DisplayName("未结束围栏不误识别代码里的来源，正文末尾空格不越界")
  void handlesIncompleteMarkdown() {
    assertThat(RagCitationValidator.check("正文[E1]\n```\n[E2]", List.of("E1")).unknownEvidenceIds())
        .isEmpty();
    assertThat(RagCitationValidator.check("   ", List.of("E1")).status()).isEqualTo(Status.NO_REFERENCES);
    assertThat(RagCitationValidator.check(null, List.of("E1")).status()).isEqualTo(Status.NO_REFERENCES);
  }
}
