package interview.guide.modules.knowledgebase.model;

/** 命名 SSE 的 JSON 数据。正文不做手工反斜杠转义。 */
public record RagStreamEventDTO(
    String event, Long messageId, String content, RagGenerationState generationState,
    String errorCode, String message) {
  public static RagStreamEventDTO start(Long messageId) {
    return new RagStreamEventDTO("start", messageId, null, RagGenerationState.GENERATING, null, null);
  }

  public static RagStreamEventDTO delta(Long messageId, String content) {
    return new RagStreamEventDTO("delta", messageId, content, null, null, null);
  }

  public static RagStreamEventDTO terminal(Long messageId, RagGenerationState state,
      String errorCode, String message) {
    return new RagStreamEventDTO("terminal", messageId, null, state, errorCode, message);
  }
}
