package interview.guide.modules.knowledgebase.model;

public record VectorExecutionClaimDTO(Status status, VectorTaskExecutionDTO execution) {
  public enum Status { CLAIMED, SKIPPED, EXHAUSTED }
}
