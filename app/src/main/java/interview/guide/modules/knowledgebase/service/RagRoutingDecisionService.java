package interview.guide.modules.knowledgebase.service;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 可解释的 RAG 路由观察器。
 *
 * <p>它刻意不调用模型，也不在默认路径中自动改写、澄清或拒答。先将少量确定的风险
 * 形态沉淀为可测决策，再由独立 Gold 集决定是否值得进入强制路径。</p>
 */
@Service
public class RagRoutingDecisionService {

    private static final List<String> CONTEXT_REFERENCE_PREFIXES = List.of(
        "这个", "这点", "这个呢", "那个", "它", "上述", "上面", "前面", "刚才", "继续"
    );

    public RagRoutingDecision plan(String question, boolean hasHistory, RagRoutingMode mode) {
        if (mode == RagRoutingMode.OFF) {
            return null;
        }
        String normalized = normalize(question);
        if (!hasHistory && isContextReferenceWithoutHistory(normalized)) {
            return new RagRoutingDecision(
                RagRouteAction.CLARIFY,
                RagRouteReason.CONTEXT_REFERENCE_WITHOUT_HISTORY,
                mode
            );
        }
        return new RagRoutingDecision(RagRouteAction.RETRIEVE, RagRouteReason.NORMAL_RETRIEVAL, mode);
    }

    public RagRoutingDecision resolve(RagRoutingDecision planned, boolean hasEffectiveHit,
                                      EvidenceAssessment evidenceAssessment) {
        if (planned == null || planned.action() == RagRouteAction.CLARIFY) {
            return planned;
        }
        if (!hasEffectiveHit) {
            return new RagRoutingDecision(
                RagRouteAction.ABSTAIN,
                RagRouteReason.NO_RETRIEVAL_EVIDENCE,
                planned.mode()
            );
        }
        if (evidenceAssessment != null && !evidenceAssessment.sufficient()) {
            return new RagRoutingDecision(
                RagRouteAction.ABSTAIN,
                RagRouteReason.INSUFFICIENT_EVIDENCE,
                planned.mode()
            );
        }
        return planned;
    }

    private boolean isContextReferenceWithoutHistory(String question) {
        if (question.length() > 12) {
            return false;
        }
        return CONTEXT_REFERENCE_PREFIXES.stream().anyMatch(question::startsWith);
    }

    private String normalize(String question) {
        return question == null ? "" : question.replaceAll("\\s+", "").trim();
    }
}
