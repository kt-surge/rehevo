package interview.guide.modules.knowledgebase.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;

/** 本轮实际供给顺序的编号；不修改文档正文或检索元数据。 */
public record RagEvidenceContext(String text, List<String> evidenceIds) {
  public RagEvidenceContext {
    evidenceIds = List.copyOf(evidenceIds);
  }

  public static RagEvidenceContext from(List<Document> documents, boolean numbered) {
    List<String> parts = new ArrayList<>();
    List<String> ids = new ArrayList<>();
    for (int index = 0; index < documents.size(); index++) {
      String body = documents.get(index).getText();
      if (numbered) {
        String id = "E" + (index + 1);
        ids.add(id);
        parts.add("[" + id + "]\n" + body);
      } else {
        parts.add(body);
      }
    }
    return new RagEvidenceContext(String.join("\n\n---\n\n", parts), ids);
  }
}
