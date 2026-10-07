package interview.guide.modules.knowledgebase.model;

import interview.guide.common.config.DocumentChunkingProperties;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseQueryProperties;
import interview.guide.modules.knowledgebase.service.RetrievalMode;

/** 实际注入的检索配置快照；不包含 API Key、Workspace 或用户标识。 */
public record RetrievalEvaluationConfigDTO(
    boolean rewriteEnabled,
    SearchConfig search,
    HybridConfig hybrid,
    ContextExpansionConfig contextExpansion,
    boolean rerankEnabled,
    String rerankModel,
    String rerankInstruction,
    boolean rerankWorkspaceConfigured,
    boolean rerankKeyConfigured,
    ChunkingConfig chunking,
    String evidenceGateMode,
    String routingMode
) {
  public static RetrievalEvaluationConfigDTO from(KnowledgeBaseQueryProperties properties,
                                                  DocumentChunkingProperties chunking) {
    var search = properties.getSearch();
    var hybrid = properties.getHybrid();
    var expansion = properties.getContextExpansion();
    return new RetrievalEvaluationConfigDTO(properties.getRewrite().isEnabled(),
        new SearchConfig(search.getMode(), search.getShortQueryLength(), search.getTopkShort(),
            search.getTopkMedium(), search.getTopkLong(), search.getMinScoreShort(),
            search.getMinScoreDefault()),
        new HybridConfig(hybrid.getVectorCandidates(), hybrid.getLexicalCandidates(),
            hybrid.getFusionCandidates(), hybrid.getRrfK(), hybrid.getVectorWeight(),
            hybrid.getLexicalWeight()),
        new ContextExpansionConfig(expansion.getSeedChunks(), expansion.getNeighborsPerSeed()),
        properties.getRerank().isEnabled(), properties.getRerank().getModel(),
        properties.getRerank().getInstruct(),
        properties.getRerank().getWorkspaceId() != null && !properties.getRerank().getWorkspaceId().isBlank(),
        properties.getRerank().getApiKey() != null && !properties.getRerank().getApiKey().isBlank(),
        new ChunkingConfig(chunking.getMode().name(), chunking.getMaxTokens()),
        properties.getEvidenceGate().getMode().name(), properties.getRouting().getMode().name());
  }

  public record SearchConfig(RetrievalMode mode, int shortQueryLength, int topkShort,
                             int topkMedium, int topkLong, double minScoreShort,
                             double minScoreDefault) {}

  public record HybridConfig(int vectorCandidates, int lexicalCandidates, int fusionCandidates,
                             int rrfK, double vectorWeight, double lexicalWeight) {}

  public record ContextExpansionConfig(int seedChunks, int neighborsPerSeed) {}
  public record ChunkingConfig(String mode, int maxTokens) {}
}
