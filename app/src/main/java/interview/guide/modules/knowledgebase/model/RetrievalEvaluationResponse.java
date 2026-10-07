package interview.guide.modules.knowledgebase.model;

import java.util.List;
import interview.guide.modules.knowledgebase.service.EvidenceAssessment;
import interview.guide.modules.knowledgebase.service.RagRoutingDecision;

/**
 * 批量检索评测响应，保留逐题证据以便离线计算 Recall、MRR 和 nDCG。
 */
public record RetrievalEvaluationResponse(
    List<RetrievalEvaluationItem> items,
    RetrievalEvaluationConfigDTO configuration
) {
    public record RetrievalEvaluationItem(
        String question,
        String retrievalQuery,
        List<QueryResponse.RetrievalEvidence> evidence,
        EvidenceAssessment evidenceAssessment,
        RagRoutingDecision routingDecision,
        List<QueryResponse.RetrievalEvidence> candidateEvidence,
        List<QueryResponse.RetrievalEvidence> vectorEvidence,
        List<QueryResponse.RetrievalEvidence> lexicalEvidence,
        double elapsedMs,
        List<FocusedQueryTraceDTO> focusedQueries,
        int vectorSearchCalls,
        Integer contextTokenBudget,
        Integer contextTokenEstimate,
        List<IdentifierReservationDTO> identifierReservations
    ) {}

    public record IdentifierReservationDTO(
        String identifier,
        String vectorDocumentId,
        boolean detailHeading
    ) {}

    public record FocusedQueryTraceDTO(
        String query,
        List<QueryResponse.RetrievalEvidence> vectorEvidence,
        List<QueryResponse.RetrievalEvidence> lexicalEvidence,
        List<QueryResponse.RetrievalEvidence> candidateEvidence
    ) {}
}
