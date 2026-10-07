package interview.guide.modules.knowledgebase.model;

/** 投递器只能回写自己实际认领的 owner/fence。 */
public record VectorTaskDeliveryDTO(Long kbId, String generation, VectorTaskInput input,
    String owner, long fence) {}
