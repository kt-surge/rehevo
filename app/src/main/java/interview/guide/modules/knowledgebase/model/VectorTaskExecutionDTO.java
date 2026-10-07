package interview.guide.modules.knowledgebase.model;

/** 固定请求的执行凭据；通知重建不能重置持久化尝试次数。 */
public record VectorTaskExecutionDTO(Long kbId, String generation, String owner, long fence,
    VectorTaskInput input, int attempt, int maxAttempts) {}
