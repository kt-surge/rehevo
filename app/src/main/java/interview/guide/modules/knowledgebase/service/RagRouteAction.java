package interview.guide.modules.knowledgebase.service;

/** 结构化 RAG 路由器给出的建议动作，不等同于默认链路的实际执行动作。 */
public enum RagRouteAction {
    RETRIEVE,
    CLARIFY,
    ABSTAIN
}
