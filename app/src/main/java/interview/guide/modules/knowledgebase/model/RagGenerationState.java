package interview.guide.modules.knowledgebase.model;

/** 新回答的生命周期；历史记录保留 null，不推断旧 completed 是否是模型成功。 */
public enum RagGenerationState {
  GENERATING, COMPLETED, FAILED, CANCELLED
}
