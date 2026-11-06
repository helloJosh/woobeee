package com.woobeee.mvc.ai.repository;

import com.woobeee.mvc.ai.entity.AiOutbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AiOutboxRepository extends JpaRepository<AiOutbox, UUID> {

    /** 발행기가 둘 떠도 같은 행을 잡지 않도록 잠근 채로 가져간다(AI-AC-04). 트랜잭션 안에서 불러야 한다. */
    @Query(value = """
            SELECT * FROM ai_outbox
             WHERE status = 'PENDING' AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<AiOutbox> lockDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE ai_outbox SET status = 'PUBLISHED' WHERE event_id = :eventId AND status = 'PENDING'",
            nativeQuery = true)
    int markPublished(@Param("eventId") UUID eventId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_outbox SET attempts = :attempts, next_attempt_at = :nextAttemptAt
             WHERE event_id = :eventId AND status = 'PENDING'
            """, nativeQuery = true)
    int scheduleRetry(@Param("eventId") UUID eventId, @Param("attempts") int attempts,
                      @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE ai_outbox SET status = 'DEAD' WHERE event_id = :eventId AND status = 'PENDING'",
            nativeQuery = true)
    int markDead(@Param("eventId") UUID eventId);
}
