package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.config.AiProperties;
import com.woobeee.mvc.ai.config.AiTopics;
import com.woobeee.mvc.ai.entity.AiOutbox;
import com.woobeee.mvc.ai.repository.AiOutboxRepository;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 아웃박스의 PENDING 행을 Kafka 로 보낸다(AI-AC-03·04).
 *
 * <p>트랜잭션 하나 안에서 행을 {@code FOR UPDATE SKIP LOCKED} 로 잠그고 보낸 뒤 상태를 바꾼다. 발행기가
 * 둘 떠도 같은 행을 잡지 않는다. 보낸 뒤 {@code PUBLISHED} 로 바꾸기 전에 죽으면 같은 이벤트가 다시 나갈 수
 * 있는데, 소비 측의 claim 이 그것을 건너뛴다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiOutboxPublisher {
    static final int BATCH_SIZE = 20;
    private static final long SEND_TIMEOUT_SECONDS = 10;
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(10);

    private final AiOutboxRepository outboxRepository;
    private final AiRequestRepository requestRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AiProperties properties;

    /**
     * 스케줄러가 프록시를 거쳐 부르므로 이 메서드의 {@code @Transactional} 이 적용된다 — 자기 호출로
     * {@link #publishOnce} 에 닿으면 프록시를 건너뛰어 잠금이 곧바로 풀리기 때문에 여기에도 붙인다.
     * 어떤 예외도 밖으로 내보내지 않는다: 다음 주기에 다시 한다.
     */
    @Scheduled(fixedDelayString = "${ai.outbox-interval:3s}")
    @Transactional
    public void tick() {
        try {
            publishOnce(LocalDateTime.now());
        } catch (RuntimeException ex) {
            log.warn("ai outbox publishing failed, will retry: {}", ex.toString());
        }
    }

    /** 이번에 PUBLISHED 로 만든 행 수. */
    @Transactional
    public int publishOnce(LocalDateTime now) {
        int published = 0;
        for (AiOutbox row : outboxRepository.lockDue(now, BATCH_SIZE)) {
            try {
                kafkaTemplate.send(AiTopics.REQUEST, row.getRequestId().toString(), payload(row))
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                outboxRepository.markPublished(row.getEventId());
                published++;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                onFailure(row, ex, now);
                break;
            } catch (Exception ex) {
                onFailure(row, ex, now);
            }
        }
        return published;
    }

    private void onFailure(AiOutbox row, Exception ex, LocalDateTime now) {
        int attempts = row.getAttempts() + 1;
        if (attempts >= properties.maxPublishAttempts()) {
            log.error("giving up publishing ai event {} after {} attempts: {}", row.getEventId(), attempts, ex.toString());
            outboxRepository.markDead(row.getEventId());
            // 요청을 FAILED 로 두면 진행 중으로 남아 사용자가 영영 막히고 재시도도 안 된다 — DEAD 로 둔다.
            requestRepository.markPublishFailed(row.getRequestId(), "publish_failed", now);
            return;
        }
        log.warn("could not publish ai event {} (attempt {}): {}", row.getEventId(), attempts, ex.toString());
        outboxRepository.scheduleRetry(row.getEventId(), attempts, now.plus(backoff(attempts)));
    }

    /** {@code min(2^n × 5초, 10분)}. */
    static Duration backoff(int attempts) {
        long seconds = 5L << Math.min(Math.max(attempts, 0), 20);
        Duration backoff = Duration.ofSeconds(seconds);
        return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
    }

    private static String payload(AiOutbox row) {
        return "{\"eventId\":\"" + row.getEventId() + "\",\"requestId\":\"" + row.getRequestId()
                + "\",\"type\":\"ai.request\",\"version\":1}";
    }
}
