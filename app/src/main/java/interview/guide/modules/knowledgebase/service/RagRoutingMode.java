package interview.guide.modules.knowledgebase.service;

/**
 * RAG 路由当前只允许关闭或观察。强制改写、澄清、拒答须由独立冻结集验证后另行启用。
 */
public enum RagRoutingMode {
    OFF,
    OBSERVE
}
