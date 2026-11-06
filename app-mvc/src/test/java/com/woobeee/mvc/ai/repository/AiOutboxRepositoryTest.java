package com.woobeee.mvc.ai.repository;

import com.woobeee.mvc.ai.entity.AiOutbox;
import com.woobeee.mvc.ai.entity.AiOutboxStatus;
import com.woobeee.mvc.ai.entity.AiRequests;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** AI-AC-03·04 — 아웃박스 조회·전이를 실 Postgres(market_test)에 대고 고정한다. 트랜잭션은 롤백된다. */
@SpringJUnitConfig(initializers = ConfigDataApplicationContextInitializer.class)
@EnableAutoConfiguration
@EntityScan(basePackages = "com.woobeee.mvc")
@EnableJpaRepositories(basePackages = "com.woobeee.mvc.ai.repository")
@Transactional
@ActiveProfiles("test")
class AiOutboxRepositoryTest {

    private static long nextMember = 9_200_100L;

    @Autowired AiOutboxRepository outbox;
    @Autowired AiRequestRepository requests;
    @Autowired EntityManager em;

    private final LocalDateTime now = LocalDateTime.of(2026, 11, 6, 12, 0);

    /** outbox 는 요청을 참조하므로 행마다 실제 요청이 필요하다. 진행 중 1건 제약 때문에 회원을 달리한다. */
    private UUID requestId() {
        return requests.saveAndFlush(AiRequests.receive(nextMember++, "t", List.of())).getId();
    }

    @Test
    void AI_AC_04_lockDue_returnsOnlyDuePendingRows() {
        AiOutbox due = outbox.saveAndFlush(AiOutbox.pending(requestId(), now.minusSeconds(1)));
        AiOutbox future = outbox.saveAndFlush(AiOutbox.pending(requestId(), now.plusMinutes(1)));
        AiOutbox published = outbox.saveAndFlush(AiOutbox.pending(requestId(), now.minusSeconds(1)));
        outbox.markPublished(published.getEventId());
        em.clear();

        assertThat(outbox.lockDue(now, 100)).extracting(AiOutbox::getEventId)
                .contains(due.getEventId())
                .doesNotContain(future.getEventId(), published.getEventId());
    }

    @Test
    void AI_AC_04_lockDue_usesSkipLocked() throws Exception {
        Query query = AiOutboxRepository.class
                .getMethod("lockDue", LocalDateTime.class, int.class).getAnnotation(Query.class);

        assertThat(query.nativeQuery()).isTrue();
        assertThat(query.value()).containsIgnoringCase("FOR UPDATE SKIP LOCKED");
    }

    @Test
    void AI_AC_03_scheduleRetryAndMarkDead() {
        AiOutbox row = outbox.saveAndFlush(AiOutbox.pending(requestId(), now));

        assertThat(outbox.scheduleRetry(row.getEventId(), 2, now.plusMinutes(1))).isEqualTo(1);
        em.clear();
        AiOutbox retried = outbox.findById(row.getEventId()).orElseThrow();
        assertThat(retried.getAttempts()).isEqualTo(2);
        assertThat(retried.getNextAttemptAt()).isEqualTo(now.plusMinutes(1));
        assertThat(retried.getStatus()).isEqualTo(AiOutboxStatus.PENDING);

        assertThat(outbox.markDead(row.getEventId())).isEqualTo(1);
        em.clear();
        assertThat(outbox.findById(row.getEventId()).orElseThrow().getStatus()).isEqualTo(AiOutboxStatus.DEAD);
        // 죽은 행은 다시 조회되지 않는다
        assertThat(outbox.lockDue(now.plusDays(1), 100)).extracting(AiOutbox::getEventId)
                .doesNotContain(row.getEventId());
    }
}
