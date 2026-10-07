package interview.guide.modules.knowledgebase.model;

import interview.guide.common.config.DocumentChunkingProperties;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

/** 正文属于固定请求版本，终态前不得通过 TTL 删除恢复依据。 */
@Entity
@Table(name = "kb_vector_tasks")
@Getter
@Setter
@NoArgsConstructor
public class VectorTaskEntity {
  @Id @Column(length = 36) private String generation;
  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "kb_id", nullable = false)
  @OnDelete(action = OnDeleteAction.CASCADE)
  private KnowledgeBaseEntity knowledgeBase;
  @Column(nullable = false, length = 64) private String fileSha256;
  @Column(nullable = false, length = 64) private String contentSha256;
  @Column(nullable = false, columnDefinition = "text") private String parsedContent;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20) private DocumentChunkingProperties.Mode chunkingMode;
  @Column(nullable = false) private int maxTokens;
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20) private State state;
  @Column(length = 36) private String deliveryOwner;
  @Column(nullable = false) private long deliveryFence;
  private Instant deliveryLeaseUntil;
  @Column(nullable = false) private int deliveryAttempts;
  @Column(nullable = false) private Instant nextDeliveryAt;
  @Column(length = 80) private String lastMessageId;
  @Column(length = 500) private String lastDeliveryError;
  @Column(nullable = false) private Instant createdAt;
  @Column(nullable = false) private Instant updatedAt;
  private Instant completedAt;
  @Column(length = 36) private String executionOwner;
  @Column(nullable = false) private long executionFence;
  private Instant executionLeaseUntil;
  @Column(nullable = false) private int executionAttempts;
  @Column(nullable = false) private int maxExecutionAttempts = 4;
  @Column(length = 500) private String lastExecutionError;

  public enum State { ACTIVE, COMPLETED, FAILED, OBSOLETE }
}
