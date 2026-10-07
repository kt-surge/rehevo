package interview.guide.modules.knowledgebase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RAG 结构化路由观察器")
class RagRoutingDecisionServiceTest {

    private final RagRoutingDecisionService service = new RagRoutingDecisionService();

    @Test
    @DisplayName("routing-observer-v1 固定样本的建议动作与原因保持可复跑")
    void replaysVersionedRoutingObserverFixture() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/rag-routing/routing-observer-v1.json")) {
            assertThat(input).isNotNull();
            RoutingFixture fixture = new ObjectMapper().readValue(input, RoutingFixture.class);
            assertThat(fixture.version()).isEqualTo("routing-observer-v1");
            assertThat(fixture.cases()).hasSize(4);

            for (RoutingCase routingCase : fixture.cases()) {
                RagRoutingDecision planned = service.plan(
                    routingCase.question(), routingCase.hasHistory(), RagRoutingMode.OBSERVE);
                EvidenceAssessment evidence = routingCase.evidenceSufficient()
                    ? sufficientEvidence() : insufficientEvidence();
                RagRoutingDecision decision = service.resolve(
                    planned, routingCase.hasEffectiveHit(), evidence);

                assertThat(decision.action()).as(routingCase.id())
                    .isEqualTo(RagRouteAction.valueOf(routingCase.expectedAction()));
                assertThat(decision.reason()).as(routingCase.id())
                    .isEqualTo(RagRouteReason.valueOf(routingCase.expectedReason()));
            }
        }
    }

    @Test
    @DisplayName("无历史的短指代问题建议澄清但不强制执行")
    void suggestsClarificationForContextReferenceWithoutHistory() {
        RagRoutingDecision decision = service.plan("这个呢？", false, RagRoutingMode.OBSERVE);

        assertThat(decision.action()).isEqualTo(RagRouteAction.CLARIFY);
        assertThat(decision.reason()).isEqualTo(RagRouteReason.CONTEXT_REFERENCE_WITHOUT_HISTORY);
        assertThat(decision.observed()).isTrue();
        assertThat(service.resolve(decision, true, sufficientEvidence()).action()).isEqualTo(RagRouteAction.CLARIFY);
    }

    @Test
    @DisplayName("有会话历史的指代问题仍按检索处理")
    void retrievesContextReferenceWhenHistoryExists() {
        RagRoutingDecision decision = service.plan("这个呢？", true, RagRoutingMode.OBSERVE);

        assertThat(decision.action()).isEqualTo(RagRouteAction.RETRIEVE);
        assertThat(decision.reason()).isEqualTo(RagRouteReason.NORMAL_RETRIEVAL);
    }

    @Test
    @DisplayName("检索无命中时建议拒答")
    void suggestsAbstentionWhenThereIsNoRetrievalEvidence() {
        RagRoutingDecision planned = service.plan("事务传播机制如何选择？", false, RagRoutingMode.OBSERVE);

        RagRoutingDecision decision = service.resolve(planned, false, null);

        assertThat(decision.action()).isEqualTo(RagRouteAction.ABSTAIN);
        assertThat(decision.reason()).isEqualTo(RagRouteReason.NO_RETRIEVAL_EVIDENCE);
    }

    @Test
    @DisplayName("证据门判为不足时建议拒答")
    void suggestsAbstentionWhenEvidenceGateIsInsufficient() {
        RagRoutingDecision planned = service.plan("默认 COUNT 是多少？", false, RagRoutingMode.OBSERVE);

        RagRoutingDecision decision = service.resolve(planned, true, insufficientEvidence());

        assertThat(decision.action()).isEqualTo(RagRouteAction.ABSTAIN);
        assertThat(decision.reason()).isEqualTo(RagRouteReason.INSUFFICIENT_EVIDENCE);
    }

    @Test
    @DisplayName("关闭时不计算和记录路由建议")
    void returnsNoDecisionWhenRoutingIsDisabled() {
        assertThat(service.plan("事务传播机制如何选择？", false, RagRoutingMode.OFF)).isNull();
    }

    private EvidenceAssessment sufficientEvidence() {
        return new EvidenceAssessment(true, EvidenceAssessmentReason.CANDIDATE_EVIDENCE, 1, false, false);
    }

    private EvidenceAssessment insufficientEvidence() {
        return new EvidenceAssessment(false, EvidenceAssessmentReason.UNSUPPORTED_NUMERIC_CONSTRAINT, 1, true, false);
    }

    private record RoutingFixture(String version, String scope, List<RoutingCase> cases) {
    }

    private record RoutingCase(String id, String question, boolean hasHistory, boolean hasEffectiveHit,
                               boolean evidenceSufficient, String expectedAction, String expectedReason) {
    }
}
