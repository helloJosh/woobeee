package com.woobeee.mvc.pokemon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** 신청서에 달리는 댓글. 신청과 마찬가지로 비회원도 쓸 수 있다. */
@Getter
@Entity
@Table(name = "pokemon_order_comments")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PokemonOrderComments {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    /** 로그인 댓글이면 회원 id, 비회원이면 null. */
    private Long memberId;

    @Column(nullable = false, length = 60)
    private String authorName;

    @Column(nullable = false, length = 500)
    private String content;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 고친 적이 있으면 그 시각, 없으면 null. 화면이 "수정됨" 을 붙일지 이 값으로 정한다. */
    private LocalDateTime updatedAt;

    @Builder
    private PokemonOrderComments(Long orderId, Long memberId, String authorName, String content) {
        this.orderId = orderId;
        this.memberId = memberId;
        this.authorName = authorName;
        this.content = content;
    }

    public static PokemonOrderComments create(Long orderId, Long memberId, String authorName, String content) {
        return PokemonOrderComments.builder()
                .orderId(orderId)
                .memberId(memberId)
                .authorName(authorName)
                .content(content)
                .build();
    }

    /**
     * 내용만 바꾼다. {@code @UpdateTimestamp} 를 쓰지 않는 이유는 그러면 다른 이유로 행이
     * 더러워져도 시각이 찍혀 "수정됨" 이 거짓말을 하기 때문이다 — 실제로 고칠 때만 찍는다.
     */
    public void edit(String content, LocalDateTime now) {
        this.content = content;
        this.updatedAt = now;
    }

    /** 비회원 댓글은 주인이 없으므로 본인 확인이 성립하지 않는다 — 운영자만 지울 수 있다. */
    public boolean isWrittenBy(Long candidateMemberId) {
        return memberId != null && memberId.equals(candidateMemberId);
    }
}
