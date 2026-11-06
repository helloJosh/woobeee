package com.woobeee.mvc.ai.repository;

import com.woobeee.mvc.ai.entity.AiRequestActions;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface AiActionRepository extends JpaRepository<AiRequestActions, Long> {

    List<AiRequestActions> findByRequestIdOrderById(UUID requestId);

    /** 같은 {@code (request_id, action_key)} 는 무시한다(AI-AC-07). 새로 들어갔으면 1, 중복이면 0. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT INTO ai_request_actions (request_id, action_key, type, payload, status, created_at)
            VALUES (:requestId, :actionKey, :type, CAST(:payload AS jsonb), 'PENDING', :createdAt)
            ON CONFLICT (request_id, action_key) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("requestId") UUID requestId, @Param("actionKey") String actionKey,
                       @Param("type") String type, @Param("payload") String payloadJson,
                       @Param("createdAt") LocalDateTime createdAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE ai_request_actions SET status = 'APPLIED' WHERE id = :id AND status = 'PENDING'",
            nativeQuery = true)
    int markApplied(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE ai_request_actions SET status = 'REJECTED' WHERE id = :id AND status = 'PENDING'",
            nativeQuery = true)
    int markRejected(@Param("id") Long id);

    @Query(value = "SELECT count(*) FROM ai_request_actions WHERE request_id = :requestId AND status = 'PENDING'",
            nativeQuery = true)
    long countPending(@Param("requestId") UUID requestId);
}
