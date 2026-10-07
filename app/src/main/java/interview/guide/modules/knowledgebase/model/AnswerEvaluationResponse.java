package interview.guide.modules.knowledgebase.model;

import java.util.List;
import interview.guide.modules.knowledgebase.service.EvidenceAssessment;

/**
 * 供离线评测器构建 RAGAS SingleTurnSample 的完整回答与上下文。
 */
public record AnswerEvaluationResponse(
    List<AnswerEvaluationItem> items
) {
    public record AnswerEvaluationItem(
        String question,
        String retrievalQuery,
        String answer,
        List<RetrievalEvidence> evidence,
        EvidenceAssessment evidenceAssessment,
        CitationValidationReport citationValidation
    ) {}

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
        String content,
        String originalFilename,
        String contentType,
        String evidenceId
    ) {}
}
