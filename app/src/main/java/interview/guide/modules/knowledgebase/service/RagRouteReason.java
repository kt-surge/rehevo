package interview.guide.modules.knowledgebase.service;

/** 路由建议的固定、可审计原因；不能写入原始问题或用户标识。 */
public enum RagRouteReason {
    NORMAL_RETRIEVAL,
    CONTEXT_REFERENCE_WITHOUT_HISTORY,
    NO_RETRIEVAL_EVIDENCE,
    INSUFFICIENT_EVIDENCE
}
