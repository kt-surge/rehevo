package interview.guide.modules.knowledgebase.service;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai.rag.stream")
public class RagChatStreamProperties {
  @Min(1)
  private int deadlineSeconds = 180;
  @Min(1)
  private int maxActiveSessions = 64;
  @Min(1)
  private int maxAnswerCharacters = 200_000;
  @Min(1)
  private int heartbeatSeconds = 15;

  public Duration deadline() {
    return Duration.ofSeconds(deadlineSeconds);
  }
}
