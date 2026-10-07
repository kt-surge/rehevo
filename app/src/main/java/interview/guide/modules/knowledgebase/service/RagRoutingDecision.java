package interview.guide.modules.knowledgebase.service;

/**
 * 单次 RAG 路由的可回放快照。
 *
 * @param action 建议动作；OBSERVE 模式下不会据此中断当前回答路径
 * @param reason 固定的、低基数触发原因
 * @param mode 当前配置模式
 */
public record RagRoutingDecision(
    RagRouteAction action,
    RagRouteReason reason,
    RagRoutingMode mode
) {
    public boolean observed() {
        return mode == RagRoutingMode.OBSERVE;
    }
}
