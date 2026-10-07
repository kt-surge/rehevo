package interview.guide.infrastructure.file;

import interview.guide.common.config.DocumentChunkingProperties;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Service;

import java.util.List;

/** 文档切块策略入口；TOKEN 完全沿用旧的 Spring AI 默认配置。 */
@Service
public class DocumentChunkingService {
  private final DocumentChunkingProperties properties;
  private final TokenTextSplitter tokenSplitter = TokenTextSplitter.builder().build();
  private final StructureAwareDocumentSplitter structuredSplitter = new StructureAwareDocumentSplitter();

  public DocumentChunkingService(DocumentChunkingProperties properties) {
    this.properties = properties;
  }

  public List<Document> split(String content) {
    return properties.getMode() == DocumentChunkingProperties.Mode.STRUCTURED
        ? structuredSplitter.split(content, properties.getMaxTokens())
        : tokenSplitter.apply(List.of(new Document(content)));
  }
}
