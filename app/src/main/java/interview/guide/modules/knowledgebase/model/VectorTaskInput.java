package interview.guide.modules.knowledgebase.model;

import interview.guide.common.config.DocumentChunkingProperties;

/** 接受事务前计算的解析正文与切块配置快照。 */
public record VectorTaskInput(String content, String contentSha256,
    DocumentChunkingProperties.Mode chunkingMode, int maxTokens) {}
