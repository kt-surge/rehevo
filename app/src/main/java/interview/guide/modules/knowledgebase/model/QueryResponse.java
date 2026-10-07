package interview.guide.modules.knowledgebase.model;

import java.util.List;
import interview.guide.modules.knowledgebase.service.EvidenceAssessment;
import interview.guide.modules.knowledgebase.service.RagRoutingDecision;

/**
 * 知识库查询响应
 */
public record QueryResponse(
    String answer,
    Long knowledgeBaseId,
    String knowledgeBaseName,
    String retrievalQuery,
    List<RetrievalEvidence> evidence,
    EvidenceAssessment evidenceAssessment,
    RagRoutingDecision routingDecision,
    CitationValidationReport citationValidation
) {

    /**
     * 单个检索候选的可追溯信息。
     * 相似度仅代表当前向量检索请求中的分数，不应跨请求比较。
     */
    public record RetrievalEvidence(
        String vectorDocumentId,
        Long knowledgeBaseId,
        String documentSha256,
        Integer chunkIndex,
        Double similarityScore,
        Integer vectorRank,
        Integer lexicalRank,
        Double rrfScore,
        Double rerankScore,
        Integer finalRank,
        List<String> retrievalSources,
        String contentPreview,
        String originalFilename,
        String contentType,
        String evidenceId
    ) {}
}
