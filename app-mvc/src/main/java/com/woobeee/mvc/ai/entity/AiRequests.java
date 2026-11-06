package com.woobeee.mvc.ai.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AI 요청 한 건. 상태 전이는 엔티티를 고치지 않고 {@code AiRequestRepository} 의 CAS 쿼리로만 한다 —
 * 워커 둘이 같은 요청을 잡거나 늦은 보고가 덮어쓰는 일을 갱신 행 수로 가려내기 위해서다.
 * 그래서 setter 가 없다.
 */
@Getter
@Entity
@Table(name = "ai_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiRequests {
    @Id
    private UUID id;

    @Column(nullable = false)
    private Long memberId;

    @Column(columnDefinition = "text")
    private String text;

    /** MinIO 오브젝트 키 목록({@code ai/{id}/{n}}). 이미지 본문은 DB 에 두지 않는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<String> imageKeys;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AiRequestStatus status;

    @Column(nullable = false)
    private int attempts;

    private LocalDateTime leaseUntil;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(columnDefinition = "text")
    private String lastError;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private AiRequests(UUID id, Long memberId, String text, List<String> imageKeys) {
        this.id = id;
        this.memberId = memberId;
        this.text = text;
        this.imageKeys = List.copyOf(imageKeys);
        this.status = AiRequestStatus.RECEIVED;
        this.createdAt = LocalDateTime.now();
    }

    public static AiRequests receive(Long memberId, String text, List<String> imageKeys) {
        return new AiRequests(UUID.randomUUID(), memberId, text, imageKeys);
    }

    /** 이미지 키는 요청 id 로 정해지므로, id 를 먼저 알아야 하는 접수 경로용. */
    public static AiRequests receive(UUID id, Long memberId, String text, List<String> imageKeys) {
        return new AiRequests(id, memberId, text, imageKeys);
    }
}
