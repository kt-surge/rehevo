package interview.guide.common.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 实验切块默认关闭；修改模式后已有知识库需要显式重新向量化。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai.rag.chunking")
public class DocumentChunkingProperties {
  private Mode mode = Mode.TOKEN;

  @Min(64)
  @Max(2048)
  private int maxTokens = 800;

  public enum Mode {
    TOKEN, STRUCTURED
  }
}
