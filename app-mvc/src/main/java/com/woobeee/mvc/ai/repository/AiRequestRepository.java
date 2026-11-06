package com.woobeee.mvc.ai.repository;

import com.woobeee.mvc.ai.entity.AiRequestStatus;
import com.woobeee.mvc.ai.entity.AiRequests;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 모든 상태 전이는 "조건이 맞을 때만 갱신" 하는 CAS 쿼리이고, 반환값(갱신 행 수)이 곧 성공 여부다.
 * 서비스는 엔티티를 읽어 판단하지 않는다 — 읽은 뒤 쓰기 사이에 다른 워커가 끼어들 수 있다.
 */
public interface AiRequestRepository extends JpaRepository<AiRequests, UUID> {

    boolean existsByMemberIdAndStatusIn(Long memberId, Collection<AiRequestStatus> statuses);

    /** 내 최근 요청 — 새로고침한 화면이 진행 중인 요청을 다시 찾는 데 쓴다. */
    java.util.Optional<AiRequests> findFirstByMemberIdOrderByCreatedAtDesc(Long memberId);

    /**
     * {@code RECEIVED}, 또는 리스가 끝난 {@code PROCESSING}·{@code FAILED} 만 잡는다. FAILED 의
     * {@code lease_until} 은 "다음 재시도 가능 시각" 이라 백오프 전의 낡은 중복 이벤트는 못 잡는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_requests
               SET status = 'PROCESSING', attempts = attempts + 1, lease_until = :leaseUntil, updated_at = :now
             WHERE id = :id
               AND (status = 'RECEIVED'
                    OR (status IN ('PROCESSING', 'FAILED') AND (lease_until IS NULL OR lease_until < :now)))
            """, nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("leaseUntil") LocalDateTime leaseUntil);

    /** 펜싱: 현재 attempt 의 PROCESSING 만 완료할 수 있다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_requests
               SET status = 'DRAFTED', summary = :summary, lease_until = NULL, last_error = NULL, updated_at = :now
             WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempt
            """, nativeQuery = true)
    int complete(@Param("id") UUID id, @Param("attempt") int attempt,
                 @Param("summary") String summary, @Param("now") LocalDateTime now);

    /** 재시도 가능 시각({@code retryAt})을 {@code lease_until} 에 둔다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_requests
               SET status = 'FAILED', last_error = :error, lease_until = :retryAt, updated_at = :now
             WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempt
            """, nativeQuery = true)
    int markFailed(@Param("id") UUID id, @Param("attempt") int attempt, @Param("error") String error,
                   @Param("retryAt") LocalDateTime retryAt, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_requests
               SET status = 'DEAD', last_error = :error, lease_until = NULL, updated_at = :now
             WHERE id = :id AND status = 'PROCESSING' AND attempts = :attempt
            """, nativeQuery = true)
    int markDead(@Param("id") UUID id, @Param("attempt") int attempt,
                 @Param("error") String error, @Param("now") LocalDateTime now);

    /** 제안이 모두 승인·거절되면 요청을 끝낸다. DRAFTED 만 APPLIED 가 된다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE ai_requests SET status = 'APPLIED', updated_at = :now WHERE id = :id AND status = 'DRAFTED'",
            nativeQuery = true)
    int markApplied(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /** 수동 재시도: 처리 횟수를 0 으로 되돌려 처음부터 다시 돈다. DEAD 만 된다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_requests
               SET status = 'RECEIVED', attempts = 0, lease_until = NULL, last_error = NULL, updated_at = :now
             WHERE id = :id AND status = 'DEAD'
            """, nativeQuery = true)
    int requeueDead(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * 발행 한도를 넘겨 이벤트가 영영 나가지 못한 요청. FAILED 로 두면 진행 중으로 남아 사용자가 막히고 재시도도
     * 안 되므로 DEAD 로 둔다 — 그래야 수동 재시도(requeueDead)로 다시 돌릴 수 있다. 아직 claim 되기 전(RECEIVED)만.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE ai_requests
               SET status = 'DEAD', last_error = :error, lease_until = NULL, updated_at = :now
             WHERE id = :id AND status = 'RECEIVED'
            """, nativeQuery = true)
    int markPublishFailed(@Param("id") UUID id, @Param("error") String error, @Param("now") LocalDateTime now);

    /** 리스가 끝났는데 아직 PROCESSING 인 요청 — 워커가 죽었거나 보고 없이 멈춘 것이다. */
    @Query(value = "SELECT * FROM ai_requests WHERE status = 'PROCESSING' AND lease_until < :now ORDER BY lease_until",
            nativeQuery = true)
    List<AiRequests> findExpiredProcessing(@Param("now") LocalDateTime now);
}
