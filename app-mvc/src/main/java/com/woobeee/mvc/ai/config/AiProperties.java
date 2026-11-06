package com.woobeee.mvc.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * @param outboxInterval    아웃박스 발행기가 도는 간격
 * @param lease             claim 한 워커에게 주는 처리 시간. 넘기면 스위퍼가 실패로 돌린다
 * @param maxPublishAttempts 발행 재시도 한도. 넘으면 outbox 는 DEAD, 요청은 FAILED
 * @param maxProcessAttempts 처리(claim) 한도. 넘으면 요청이 DEAD
 * @param maxImages         요청당 이미지 수
 * @param maxImageBytes     이미지 한 장의 크기 한도
 * @param workerSecret      워커가 내부 API 에 내미는 시크릿. 비어 있으면 내부 API 는 전부 401 이다
 * @param managerMemberIds  남의 DEAD 요청도 다시 돌릴 수 있는 전역 운영자. pokemon 명단과 값이 같아도 따로 둔다
 *                          — 도메인 간 의존을 늘리지 않기 위해서다
 */
@ConfigurationProperties(prefix = "ai")
public record AiProperties(
        Duration outboxInterval,
        Duration lease,
        int maxPublishAttempts,
        int maxProcessAttempts,
        int maxImages,
        long maxImageBytes,
        String workerSecret,
        Set<Long> managerMemberIds
) {
    public static final List<String> ALLOWED_IMAGE_TYPES = List.of("image/png", "image/jpeg", "image/webp");

    public AiProperties {
        if (outboxInterval == null) {
            outboxInterval = Duration.ofSeconds(3);
        }
        if (lease == null) {
            lease = Duration.ofMinutes(5);
        }
        if (maxPublishAttempts <= 0) {
            maxPublishAttempts = 8;
        }
        if (maxProcessAttempts <= 0) {
            maxProcessAttempts = 3;
        }
        if (maxImages <= 0) {
            maxImages = 5;
        }
        if (maxImageBytes <= 0) {
            maxImageBytes = 5L * 1024 * 1024;
        }
        if (workerSecret == null) {
            workerSecret = "";
        }
        if (managerMemberIds == null || managerMemberIds.isEmpty()) {
            managerMemberIds = Set.of(1L);
        }
    }

    public boolean isManager(Long memberId) {
        return memberId != null && managerMemberIds.contains(memberId);
    }
}
