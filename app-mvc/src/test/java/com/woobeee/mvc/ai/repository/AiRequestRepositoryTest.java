package com.woobeee.mvc.ai.repository;

import com.woobeee.mvc.ai.entity.AiActionStatus;
import com.woobeee.mvc.ai.entity.AiRequestStatus;
import com.woobeee.mvc.ai.entity.AiRequests;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI-AC-05·06·07·08·11·19 — 요청 상태 전이(CAS)를 실 Postgres(market_test)에 대고 고정한다.
 * 트랜잭션은 롤백되고, 테스트 전용 회원 id 대역(9_200_000+)만 쓴다.
 */
@SpringJUnitConfig(initializers = ConfigDataApplicationContextInitializer.class)
@EnableAutoConfiguration
@EntityScan(basePackages = "com.woobeee.mvc")
@EnableJpaRepositories(basePackages = "com.woobeee.mvc.ai.repository")
@Transactional
@ActiveProfiles("test")
class AiRequestRepositoryTest {

    private static final long ME = 9_200_001L;
    private static final long SOMEONE_ELSE = 9_200_002L;

    @Autowired AiRequestRepository requests;
    @Autowired AiActionRepository actions;
    @Autowired EntityManager em;

    private final LocalDateTime now = LocalDateTime.of(2026, 11, 6, 12, 0);

    private AiRequests received(long memberId) {
        return requests.saveAndFlush(AiRequests.receive(memberId, "GO패스 디럭스 추가", List.of("ai/x/0")));
    }

    /** 상태 전이 직전의 조건을 만들기 위해 컬럼을 직접 맞춘다. */
    private void force(UUID id, String status, int attempts, LocalDateTime leaseUntil) {
        em.createNativeQuery("UPDATE ai_requests SET status = :s, attempts = :a, lease_until = :l WHERE id = :id")
                .setParameter("s", status).setParameter("a", attempts)
                .setParameter("l", leaseUntil).setParameter("id", id).executeUpdate();
        em.clear();
    }

    private AiRequests reload(UUID id) {
        em.clear();
        return requests.findById(id).orElseThrow();
    }

    @Test
    void AI_AC_05_claim_onlyFromReceivedOrExpired() {
        AiRequests r = received(ME);

        assertThat(requests.claim(r.getId(), now, now.plusMinutes(5))).isEqualTo(1);
        AiRequests claimed = reload(r.getId());
        assertThat(claimed.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(claimed.getAttempts()).isEqualTo(1);

        // 리스가 살아 있는 동안은 다시 잡을 수 없다
        assertThat(requests.claim(r.getId(), now, now.plusMinutes(5))).isZero();

        // 리스가 만료되면 이어받고 attempts 가 오른다
        force(r.getId(), "PROCESSING", 1, now.minusSeconds(1));
        assertThat(requests.claim(r.getId(), now, now.plusMinutes(5))).isEqualTo(1);
        assertThat(reload(r.getId()).getAttempts()).isEqualTo(2);

        // 끝난 요청은 잡을 수 없다
        for (String done : List.of("DRAFTED", "APPLIED", "DEAD")) {
            force(r.getId(), done, 2, now.minusDays(1));
            assertThat(requests.claim(r.getId(), now, now.plusMinutes(5))).as(done).isZero();
        }
    }

    @Test
    void AI_AC_06_complete_requiresCurrentAttempt() {
        AiRequests r = received(ME);
        force(r.getId(), "PROCESSING", 2, now.plusMinutes(5));

        assertThat(requests.complete(r.getId(), 1, "낡은 보고", now)).isZero();
        assertThat(reload(r.getId()).getStatus()).isEqualTo(AiRequestStatus.PROCESSING);

        assertThat(requests.complete(r.getId(), 2, "상품 3개를 찾았습니다", now)).isEqualTo(1);
        AiRequests done = reload(r.getId());
        assertThat(done.getStatus()).isEqualTo(AiRequestStatus.DRAFTED);
        assertThat(done.getSummary()).isEqualTo("상품 3개를 찾았습니다");
    }

    @Test
    void AI_AC_08_markFailed_setsRetryAtAsLease() {
        AiRequests r = received(ME);
        force(r.getId(), "PROCESSING", 1, now.plusMinutes(5));
        LocalDateTime retryAt = now.plusSeconds(30);

        assertThat(requests.markFailed(r.getId(), 1, "timeout", retryAt, now)).isEqualTo(1);
        AiRequests failed = reload(r.getId());
        assertThat(failed.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(failed.getLeaseUntil()).isEqualTo(retryAt);
        assertThat(failed.getLastError()).isEqualTo("timeout");

        // 백오프가 끝나기 전의 낡은 중복 이벤트는 잡지 못하고, 끝난 뒤에는 잡는다
        assertThat(requests.claim(r.getId(), retryAt.minusSeconds(1), retryAt.plusMinutes(5))).isZero();
        assertThat(requests.claim(r.getId(), retryAt.plusSeconds(1), retryAt.plusMinutes(5))).isEqualTo(1);
    }

    @Test
    void AI_AC_08_markDead_requiresCurrentAttemptAndProcessing() {
        AiRequests r = received(ME);
        force(r.getId(), "PROCESSING", 3, now.plusMinutes(5));

        assertThat(requests.markDead(r.getId(), 2, "x", now)).isZero();
        assertThat(requests.markDead(r.getId(), 3, "gave up", now)).isEqualTo(1);
        assertThat(reload(r.getId()).getStatus()).isEqualTo(AiRequestStatus.DEAD);
    }

    @Test
    void AI_AC_10_requeueDead_resetsOnlyDeadRequests() {
        AiRequests r = received(ME);
        force(r.getId(), "DRAFTED", 1, null);
        assertThat(requests.requeueDead(r.getId(), now)).isZero();

        force(r.getId(), "DEAD", 3, null);
        assertThat(requests.requeueDead(r.getId(), now)).isEqualTo(1);

        AiRequests again = reload(r.getId());
        assertThat(again.getStatus()).isEqualTo(AiRequestStatus.RECEIVED);
        assertThat(again.getAttempts()).isZero();
        assertThat(again.getLastError()).isNull();
        assertThat(requests.requeueDead(r.getId(), now)).isZero();
    }

    @Test
    void AI_AC_03_markPublishFailed_onlyForUnclaimedRequestsAndLeavesItRetryable() {
        AiRequests r = received(ME);

        assertThat(requests.markPublishFailed(r.getId(), "publish_failed", now)).isEqualTo(1);
        AiRequests dead = reload(r.getId());
        assertThat(dead.getStatus()).isEqualTo(AiRequestStatus.DEAD);
        assertThat(dead.getLastError()).isEqualTo("publish_failed");
        // DEAD 는 진행 중이 아니므로 사용자가 막히지 않고, 수동 재시도로 되살릴 수 있다
        assertThat(requests.existsByMemberIdAndStatusIn(ME, List.of(AiRequestStatus.RECEIVED,
                AiRequestStatus.PROCESSING, AiRequestStatus.FAILED))).isFalse();
        assertThat(requests.requeueDead(r.getId(), now)).isEqualTo(1);

        // 이미 claim 된 요청은 건드리지 않는다
        force(r.getId(), "PROCESSING", 1, now.plusMinutes(5));
        assertThat(requests.markPublishFailed(r.getId(), "publish_failed", now)).isZero();
    }

    @Test
    void AI_AC_03_markPublishFailed_alsoRescuesAFailedRequestWhoseRetryEventCouldNotBePublished() {
        AiRequests r = received(ME);
        // 처리 실패 뒤 재시도 이벤트를 기다리는 요청(FAILED). 그 이벤트의 발행이 한도를 넘으면 아무도 이 요청을 건드리지 않는다
        force(r.getId(), "FAILED", 1, now.plusSeconds(30));
        assertThat(requests.existsByMemberIdAndStatusIn(ME, List.of(AiRequestStatus.FAILED))).isTrue();

        assertThat(requests.markPublishFailed(r.getId(), "publish_failed", now)).isEqualTo(1);

        AiRequests dead = reload(r.getId());
        assertThat(dead.getStatus()).isEqualTo(AiRequestStatus.DEAD);
        assertThat(dead.getLeaseUntil()).isNull();
        // 사용자는 더 이상 막히지 않고 수동 재시도로 되살릴 수 있다
        assertThat(requests.existsByMemberIdAndStatusIn(ME, List.of(AiRequestStatus.RECEIVED,
                AiRequestStatus.PROCESSING, AiRequestStatus.FAILED))).isFalse();
        assertThat(requests.requeueDead(r.getId(), now)).isEqualTo(1);
    }

    @Test
    void AI_AC_09_findExpiredProcessing_returnsOnlyExpiredProcessing() {
        AiRequests expired = received(ME);
        force(expired.getId(), "PROCESSING", 1, now.minusSeconds(1));
        AiRequests alive = received(SOMEONE_ELSE);
        force(alive.getId(), "PROCESSING", 1, now.plusMinutes(1));

        assertThat(requests.findExpiredProcessing(now)).extracting(AiRequests::getId)
                .contains(expired.getId()).doesNotContain(alive.getId());
    }

    @Test
    void AI_AC_07_insertIfAbsent_ignoresDuplicateActionKey() {
        AiRequests r = received(ME);

        assertThat(actions.insertIfAbsent(r.getId(), "ROUND_PRODUCTS:1:go패스", "ROUND_PRODUCTS", "{\"name\":\"GO패스\"}", now)).isEqualTo(1);
        assertThat(actions.insertIfAbsent(r.getId(), "ROUND_PRODUCTS:1:go패스", "ROUND_PRODUCTS", "{\"name\":\"GO패스\"}", now)).isZero();

        em.clear();
        assertThat(actions.findByRequestIdOrderById(r.getId())).hasSize(1);
        assertThat(actions.findByRequestIdOrderById(r.getId()).get(0).getStatus()).isEqualTo(AiActionStatus.PENDING);
    }

    @Test
    void AI_AC_11_markApplied_isCompareAndSet() {
        AiRequests r = received(ME);
        actions.insertIfAbsent(r.getId(), "k", "TASK", "{\"name\":\"병원\"}", now);
        Long actionId = actions.findByRequestIdOrderById(r.getId()).get(0).getId();

        assertThat(actions.countPending(r.getId())).isEqualTo(1);
        assertThat(actions.markApplied(actionId)).isEqualTo(1);
        assertThat(actions.markApplied(actionId)).isZero();
        assertThat(actions.markRejected(actionId)).isZero();
        assertThat(actions.countPending(r.getId())).isZero();
    }

    @Test
    void AI_AC_19_secondInFlightRequestIsRejectedByTheDatabase() {
        received(ME);
        received(SOMEONE_ELSE); // 다른 회원은 영향이 없다

        // 위반이 나면 이 트랜잭션은 더 쓸 수 없으므로 마지막에 단언한다
        assertThatThrownBy(() -> received(ME)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void AI_AC_19_settledRequestsDoNotBlockANewOne() {
        AiRequests first = received(ME);

        for (String settled : List.of("DRAFTED", "APPLIED", "DEAD")) {
            force(first.getId(), settled, 1, null);
            AiRequests next = received(ME);
            force(next.getId(), "DRAFTED", 1, null);
        }
    }

    @Test
    void AI_AC_15_findFirstByMemberIdOrderByCreatedAtDesc_returnsTheNewestOfMine() {
        AiRequests older = received(ME);
        force(older.getId(), "APPLIED", 1, null);
        em.createNativeQuery("UPDATE ai_requests SET created_at = :t WHERE id = :id")
                .setParameter("t", now.minusDays(1)).setParameter("id", older.getId()).executeUpdate();
        AiRequests newer = received(ME);
        em.createNativeQuery("UPDATE ai_requests SET created_at = :t WHERE id = :id")
                .setParameter("t", now).setParameter("id", newer.getId()).executeUpdate();
        received(SOMEONE_ELSE);
        em.clear();

        assertThat(requests.findFirstByMemberIdOrderByCreatedAtDesc(ME)).get()
                .extracting(AiRequests::getId).isEqualTo(newer.getId());
        assertThat(requests.findFirstByMemberIdOrderByCreatedAtDesc(9_299_999L)).isEmpty();
    }

    @Test
    void AI_AC_02_existsByMemberIdAndStatusIn_seesOnlyInFlight() {
        AiRequests r = received(ME);
        List<AiRequestStatus> inFlight = List.of(AiRequestStatus.RECEIVED, AiRequestStatus.PROCESSING, AiRequestStatus.FAILED);

        assertThat(requests.existsByMemberIdAndStatusIn(ME, inFlight)).isTrue();
        assertThat(requests.existsByMemberIdAndStatusIn(SOMEONE_ELSE, inFlight)).isFalse();

        force(r.getId(), "DRAFTED", 1, null);
        assertThat(requests.existsByMemberIdAndStatusIn(ME, inFlight)).isFalse();
    }
}
