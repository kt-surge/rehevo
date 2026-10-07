/** 编号存在不等于该来源支持断言。 */
export interface CitationValidationReport {
  status: 'DISABLED' | 'NO_REFERENCES' | 'REFERENCES_KNOWN' | 'UNKNOWN_REFERENCES';
  referencedEvidenceIds: string[];
  unknownEvidenceIds: string[];
}

export interface RagCitationOptions {
  evidenceIds: string[];
  scope: string;
}
