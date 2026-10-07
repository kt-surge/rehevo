package interview.guide.modules.knowledgebase.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 证据充分性观察门")
class EvidenceSufficiencyServiceTest {

    private final EvidenceSufficiencyService service = new EvidenceSufficiencyService();

    @Test
    @DisplayName("没有候选时明确判为证据不足")
    void rejectsMissingCandidate() {
        EvidenceAssessment assessment = service.assess("Spring 默认事务超时是多少秒？", List.of());

        assertThat(assessment.sufficient()).isFalse();
        assertThat(assessment.reason()).isEqualTo(EvidenceAssessmentReason.NO_CANDIDATE);
    }

    @Test
    @DisplayName("精确数值问题没有数值证据时标记为待拒答")
    void detectsUnsupportedNumericConstraint() {
        Document genericSpringDocument = Document.builder()
            .id("spring-overview")
            .text("Spring 支持通过事务代理管理事务边界。")
            .build();

        EvidenceAssessment assessment = service.assess("Spring 默认事务超时具体是多少秒？", List.of(genericSpringDocument));

        assertThat(assessment.sufficient()).isFalse();
        assertThat(assessment.reason()).isEqualTo(EvidenceAssessmentReason.UNSUPPORTED_NUMERIC_CONSTRAINT);
        assertThat(assessment.numericConstraintDetected()).isTrue();
    }

    @Test
    @DisplayName("候选包含数值时保留给后续回答链路")
    void acceptsNumericEvidenceForExactValueQuestion() {
        Document document = Document.builder()
            .id("stream-count")
            .text("该配置默认 COUNT 为 100，每次最多处理 100 条记录。")
            .build();

        EvidenceAssessment assessment = service.assess("默认 COUNT 是多少？", List.of(document));

        assertThat(assessment.sufficient()).isTrue();
        assertThat(assessment.reason()).isEqualTo(EvidenceAssessmentReason.CANDIDATE_EVIDENCE);
        assertThat(assessment.numericEvidencePresent()).isTrue();
    }

    @Test
    @DisplayName("中文的一次或一条不能充当精确数值证据")
    void doesNotTreatOrdinaryChineseQuantifierAsNumericEvidence() {
        Document document = Document.builder()
            .id("stream-overview")
            .text("一次处理后再确认；一条消息可被重复投递。")
            .build();

        EvidenceAssessment assessment = service.assess("XAUTOCLAIM 默认 COUNT 是多少？", List.of(document));

        assertThat(assessment.sufficient()).isFalse();
        assertThat(assessment.reason()).isEqualTo(EvidenceAssessmentReason.UNSUPPORTED_NUMERIC_CONSTRAINT);
        assertThat(assessment.numericEvidencePresent()).isFalse();
    }

    @Test
    @DisplayName("线上事实需要候选明确包含运行或生产证据")
    void requiresOperationalEvidenceForProductionClaim() {
        Document genericDocument = Document.builder()
            .id("generic-rag")
            .text("Cross-Encoder 对候选 Chunk 做相关性排序。")
            .build();

        EvidenceAssessment assessment = service.assess("Cross-Encoder 在当前线上使用的具体模型名称是什么？",
            List.of(genericDocument));

        assertThat(assessment.sufficient()).isFalse();
        assertThat(assessment.reason()).isEqualTo(EvidenceAssessmentReason.UNSUPPORTED_CONFIGURATION_ASSERTION);
    }

    @Test
    @DisplayName("数值必须和提问的技术锚点出现在同一候选中")
    void requiresNumericEvidenceAlongsideTechnicalAnchor() {
        Document xautoClaimDocument = Document.builder()
            .id("xautoclaim")
            .text("XAUTOCLAIM 只接管超过 min-idle-time 的 Pending 条目。")
            .build();
        Document unrelatedCountDocument = Document.builder()
            .id("count")
            .text("默认 COUNT 为 100，每次最多处理 100 条记录。")
            .build();

        EvidenceAssessment assessment = service.assess(
            "XAUTOCLAIM 的 min-idle-time 固定为多少毫秒？",
            List.of(xautoClaimDocument, unrelatedCountDocument)
        );

        assertThat(assessment.sufficient()).isFalse();
        assertThat(assessment.reason()).isEqualTo(EvidenceAssessmentReason.UNSUPPORTED_NUMERIC_CONSTRAINT);
    }
}
