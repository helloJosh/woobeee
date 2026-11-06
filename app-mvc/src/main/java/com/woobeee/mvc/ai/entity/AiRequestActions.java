package com.woobeee.mvc.ai.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * AI 가 제안한 등록 한 건. 만들기만 하고 읽기 전용이다 — 쓰기는 {@code AiActionRepository} 의 쿼리
 * ({@code insertIfAbsent}, CAS) 로만 한다.
 */
@Getter
@Entity
@Table(name = "ai_request_actions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiRequestActions {
    @Id
    private Long id;

    @Column(nullable = false)
    private UUID requestId;

    @Column(nullable = false, length = 100)
    private String actionKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AiActionType type;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiActionStatus status;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
