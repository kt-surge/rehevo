package interview.guide.modules.knowledgebase.service;

/**
 * Deterministic, explainable evidence-sufficiency observation for one retrieval result.
 * It is deliberately not an LLM judge: its initial purpose is to produce stable,
 * replayable features for calibrating the later enforcement gate on Gold data.
 */
public record EvidenceAssessment(
    boolean sufficient,
    EvidenceAssessmentReason reason,
    int candidateCount,
    boolean numericConstraintDetected,
    boolean numericEvidencePresent
) {
}
