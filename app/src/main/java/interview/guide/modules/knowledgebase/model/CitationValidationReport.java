package interview.guide.modules.knowledgebase.model;

import java.util.List;

/** 仅检查引用编号是否属于本轮供给片段，不代表断言支持或忠实度。 */
public record CitationValidationReport(
    Status status, List<String> referencedEvidenceIds, List<String> unknownEvidenceIds) {
  public CitationValidationReport {
    referencedEvidenceIds = List.copyOf(referencedEvidenceIds);
    unknownEvidenceIds = List.copyOf(unknownEvidenceIds);
  }

  public enum Status {
    DISABLED, NO_REFERENCES, REFERENCES_KNOWN, UNKNOWN_REFERENCES
  }
}
