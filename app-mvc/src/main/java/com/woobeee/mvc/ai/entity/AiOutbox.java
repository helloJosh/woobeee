package com.woobeee.mvc.ai.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/** 발행해야 할 이벤트 한 건. 요청과 같은 트랜잭션에 저장된다(AI-AC-01). 상태 전이는 CAS 쿼리로만 한다. */
@Getter
@Entity
@Table(name = "ai_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiOutbox {
    @Id
    private UUID eventId;

    @Column(nullable = false)
    private UUID requestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiOutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private AiOutbox(UUID requestId, LocalDateTime nextAttemptAt) {
        this.eventId = UUID.randomUUID();
        this.requestId = requestId;
        this.status = AiOutboxStatus.PENDING;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = LocalDateTime.now();
    }

    public static AiOutbox pending(UUID requestId, LocalDateTime nextAttemptAt) {
        return new AiOutbox(requestId, nextAttemptAt);
    }
}
