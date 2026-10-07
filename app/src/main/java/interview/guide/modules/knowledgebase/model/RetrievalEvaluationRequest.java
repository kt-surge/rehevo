package interview.guide.modules.knowledgebase.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import interview.guide.modules.knowledgebase.service.RetrievalMode;

import java.util.List;

/**
 * 仅用于离线 RAG 检索评测的批量请求，不触发回答生成或问题计数。
 */
public record RetrievalEvaluationRequest(
    @NotEmpty(message = "至少提供一条评测问题")
    @Size(max = 100, message = "单次最多评测100条问题")
    List<@Valid QueryRequest> queries,
    Boolean rewrite,
    RetrievalMode retrievalMode,
    @Min(value = 1, message = "上下文预算必须为正数")
    @Max(value = 16000, message = "评测上下文预算最多16000")
    Integer contextTokenBudget
) {
    public RetrievalEvaluationRequest(List<QueryRequest> queries, Boolean rewrite,
                                      RetrievalMode retrievalMode) {
        this(queries, rewrite, retrievalMode, null);
    }
    public boolean useRewrite() {
        return rewrite == null || rewrite;
    }
}
