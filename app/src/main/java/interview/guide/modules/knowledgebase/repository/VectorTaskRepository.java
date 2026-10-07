package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.VectorTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface VectorTaskRepository extends JpaRepository<VectorTaskEntity, String> {
  @Query(value = "SELECT clock_timestamp()", nativeQuery = true)
  Instant databaseNow();

  @Query(value = """
      SELECT t.* FROM kb_vector_tasks t JOIN knowledge_bases k ON k.id=t.kb_id
      WHERE t.state='ACTIVE' AND k.vector_generation=t.generation AND k.vector_status<>'COMPLETED'
        AND t.next_delivery_at<=CURRENT_TIMESTAMP
        AND (t.delivery_lease_until IS NULL OR t.delivery_lease_until<CURRENT_TIMESTAMP)
        AND (t.execution_lease_until IS NULL OR t.execution_lease_until<clock_timestamp())
      ORDER BY t.next_delivery_at,t.created_at,t.generation
      LIMIT 1 FOR UPDATE OF t SKIP LOCKED
      """, nativeQuery = true)
  List<VectorTaskEntity> lockNextDelivery();

  @Query(value = "SELECT t.* FROM kb_vector_tasks t WHERE t.generation=:generation FOR UPDATE", nativeQuery = true)
  Optional<VectorTaskEntity> lockExecution(@Param("generation") String generation);

  @Query(value = """
      SELECT count(*)>0 FROM kb_vector_tasks t JOIN knowledge_bases k ON k.id=t.kb_id
      WHERE t.kb_id=:kbId AND t.generation=:generation AND t.execution_owner=:owner
        AND t.execution_fence=:fence AND t.state='ACTIVE'
        AND t.execution_lease_until>clock_timestamp() AND k.vector_generation=t.generation
        AND k.vector_status='PROCESSING'
      """, nativeQuery = true)
  boolean executionOwned(@Param("kbId") Long kbId, @Param("generation") String generation,
      @Param("owner") String owner, @Param("fence") long fence);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE VectorTaskEntity t SET t.state="
      + "interview.guide.modules.knowledgebase.model.VectorTaskEntity.State.COMPLETED, "
      + "t.updatedAt=:now, t.completedAt=:now, t.executionOwner=NULL, t.executionLeaseUntil=NULL "
      + "WHERE t.knowledgeBase.id=:kbId AND t.generation=:generation AND t.executionOwner=:owner "
      + "AND t.executionFence=:fence AND t.executionLeaseUntil>:now AND t.state="
      + "interview.guide.modules.knowledgebase.model.VectorTaskEntity.State.ACTIVE")
  int completeExecution(@Param("kbId") Long kbId, @Param("generation") String generation,
      @Param("owner") String owner, @Param("fence") long fence, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE VectorTaskEntity t SET t.state=:state, t.updatedAt=:now, t.completedAt=:now "
      + "WHERE t.knowledgeBase.id=:kbId AND t.generation=:generation AND t.state="
      + "interview.guide.modules.knowledgebase.model.VectorTaskEntity.State.ACTIVE")
  int finishTask(@Param("kbId") Long kbId, @Param("generation") String generation,
      @Param("state") VectorTaskEntity.State state, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE VectorTaskEntity t SET t.state="
      + "interview.guide.modules.knowledgebase.model.VectorTaskEntity.State.OBSOLETE, t.updatedAt=:now "
      + "WHERE t.knowledgeBase.id=:kbId AND t.generation<>:generation AND t.state="
      + "interview.guide.modules.knowledgebase.model.VectorTaskEntity.State.ACTIVE")
  int obsoletePrevious(@Param("kbId") Long kbId, @Param("generation") String generation, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE VectorTaskEntity t SET t.lastMessageId=:messageId, t.lastDeliveryError=:error, "
      + "t.nextDeliveryAt=:next, t.deliveryAttempts=t.deliveryAttempts+1, t.updatedAt=:now "
      + "WHERE t.generation=:generation AND t.deliveryFence=0 AND t.state="
      + "interview.guide.modules.knowledgebase.model.VectorTaskEntity.State.ACTIVE")
  int recordInitialDelivery(@Param("generation") String generation, @Param("messageId") String messageId,
      @Param("error") String error, @Param("next") Instant next, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE VectorTaskEntity t SET t.lastMessageId=:messageId, t.lastDeliveryError=:error, "
      + "t.nextDeliveryAt=:next, t.deliveryOwner=NULL, t.deliveryLeaseUntil=NULL, t.updatedAt=:now "
      + "WHERE t.generation=:generation AND t.deliveryOwner=:owner AND t.deliveryFence=:fence AND t.state="
      + "interview.guide.modules.knowledgebase.model.VectorTaskEntity.State.ACTIVE")
  int finishDelivery(@Param("generation") String generation, @Param("owner") String owner,
      @Param("fence") long fence, @Param("messageId") String messageId, @Param("error") String error,
      @Param("next") Instant next, @Param("now") Instant now);
}
