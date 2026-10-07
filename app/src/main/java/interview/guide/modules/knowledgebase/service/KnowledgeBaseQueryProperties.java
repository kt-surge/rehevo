package interview.guide.modules.knowledgebase.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.ai.rag")
public class KnowledgeBaseQueryProperties {

    private Rewrite rewrite = new Rewrite();
    private Search search = new Search();
    private Hybrid hybrid = new Hybrid();
    private ContextExpansion contextExpansion = new ContextExpansion();
    private Rerank rerank = new Rerank();
    private EvidenceGate evidenceGate = new EvidenceGate();
    private Routing routing = new Routing();
    private History history = new History();
    private Citation citation = new Citation();
    private String systemPromptPath = "classpath:prompts/knowledgebase-query-system.st";
    private String userPromptPath = "classpath:prompts/knowledgebase-query-user.st";
    private String rewritePromptPath = "classpath:prompts/knowledgebase-query-rewrite.st";

    @Data
    public static class Citation {
        /** 先验证断言支持与覆盖率，再决定是否默认开启。 */
        private boolean enabled = false;
        private String promptPath = "classpath:prompts/knowledgebase-citation-rules.st";
    }

    @Data
    public static class Rewrite {
        private boolean enabled = false;
    }

    @Data
    public static class Search {
        private RetrievalMode mode = RetrievalMode.HYBRID;
        private int shortQueryLength = 4;
        private int topkShort = 20;
        private int topkMedium = 12;
        private int topkLong = 8;
        private double minScoreShort = 0.25;
        private double minScoreDefault = 0.28;
    }

    @Data
    public static class Hybrid {
        private int vectorCandidates = 20;
        private int lexicalCandidates = 20;
        private int fusionCandidates = 20;
        private int rrfK = 60;
        private double vectorWeight = 3.0;
        private double lexicalWeight = 1.0;
        private boolean initializeSchema = true;
    }

    @Data
    public static class ContextExpansion {
        /** 仅由 HYBRID_CONTEXT 显式启用，默认链路不扩展相邻 Chunk。 */
        private int seedChunks = 2;
        private int neighborsPerSeed = 2;
    }

    @Data
    public static class Rerank {
        private boolean enabled = false;
        private String workspaceId = "";
        private String apiKey = "";
        private String model = "qwen3-rerank";
        private String instruct = "Given a web search query, retrieve relevant passages that answer the query.";
    }

    @Data
    public static class History {
        private boolean enabled = true;
        private int maxMessages = 10;
    }

    @Data
    public static class EvidenceGate {
        /** OFF: 不计算；OBSERVE: 返回并记录但不拦截；ENFORCE: 证据不足时拒答。 */
        private EvidenceGateMode mode = EvidenceGateMode.OBSERVE;
    }

    @Data
    public static class Routing {
        /** 默认只返回并记录路由建议；不因建议改变检索、澄清或拒答行为。 */
        private RagRoutingMode mode = RagRoutingMode.OBSERVE;
    }
}
