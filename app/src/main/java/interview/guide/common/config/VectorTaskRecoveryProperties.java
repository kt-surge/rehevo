package interview.guide.common.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** 并发执行和恢复验收完成之前，持久化恢复保持显式实验开关。 */
@Data
@Validated
@Component
@ConfigurationProperties(prefix = "app.ai.rag.vector-task")
public class VectorTaskRecoveryProperties {
  private boolean durableEnabled;
  @Min(1000) private long deliveryLeaseMs = 10000;
  @Min(1000) private long redeliveryDelayMs = 30000;
  @Min(1000) private long retryDelayMs = 2000;
  @Min(3000) private long executionLeaseMs = 90000;
  @Min(1000) private long heartbeatDelayMs = 10000;
  @Min(1) @Max(4) private int maxExecutionAttempts = 4;

  @AssertTrue(message = "心跳间隔必须小于执行租约的三分之一")
  public boolean isExecutionTimingValid() {
    return heartbeatDelayMs < executionLeaseMs / 3;
  }
}
