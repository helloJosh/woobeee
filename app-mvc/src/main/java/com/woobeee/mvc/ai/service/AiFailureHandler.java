package com.woobeee.mvc.ai.service;

import com.woobeee.mvc.ai.config.AiProperties;
import com.woobeee.mvc.ai.config.AiTopics;
import com.woobeee.mvc.ai.entity.AiOutbox;
import com.woobeee.mvc.ai.entity.AiRequests;
import com.woobeee.mvc.ai.repository.AiOutboxRepository;
import com.woobeee.mvc.ai.repository.AiRequestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 처리 실패를 한 곳에서 다룬다(AI-AC-08). 워커의 fail 보고와 스위퍼의 리스 만료가 같은 경로를 탄다.
 *
 * <p>처리 한도 미만이면 {@code FAILED} 로 두고 백오프 뒤에 같은 토픽으로 다시 발행한다(재시도 토픽 없음).
 * 한도에 닿으면 {@code DEAD} 로 두고 DLQ 에 기록 이벤트를 남긴다 — DLQ 발행이 실패해도 상태 전이는 유지한다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiFailureHandler {
    private final AiRequestRepository requestRepository;
    private final AiOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AiProperties properties;

    /** {@code request.getAttempts()} 가 이번 실패한 시도의 번호다(펜싱 값). */
    public void failOrDie(AiRequests request, String error, LocalDateTime now) {
        int attempt = request.getAttempts();
        if (attempt < properties.maxProcessAttempts()) {
            LocalDateTime retryAt = now.plus(AiOutboxPublisher.backoff(attempt));
            // retryAt 은 lease_until 이 되어 그 전의 낡은 중복 이벤트가 claim 하지 못하게 한다. 새 이벤트는 그보다
            // 1초 뒤에 나가야 한다 — 같은 순간이면 claim 의 "lease_until < now" 를 못 넘어 409 로 건너뛰게 된다.
            if (requestRepository.markFailed(request.getId(), attempt, error, retryAt, now) == 1) {
                outboxRepository.save(AiOutbox.pending(request.getId(), retryAt.plusSeconds(1)));
            }
            return;
        }
        if (requestRepository.markDead(request.getId(), attempt, error, now) == 1) {
            log.error("ai request {} is dead after {} attempts: {}", request.getId(), attempt, error);
            writeDlq(request.getId().toString(), attempt, error, now);
        }
    }

    private void writeDlq(String requestId, int attempts, String error, LocalDateTime now) {
        try {
            kafkaTemplate.send(AiTopics.DLQ, requestId, "{\"requestId\":\"" + requestId + "\",\"attempts\":" + attempts
                    + ",\"error\":\"" + escape(error) + "\",\"at\":\"" + now + "\"}");
        } catch (RuntimeException ex) {
            log.warn("could not write the ai dlq record for {}: {}", requestId, ex.toString());
        }
    }

    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (char c : text.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? " " : String.valueOf(c));
            }
        }
        return out.toString();
    }
}
