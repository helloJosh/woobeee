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

    /** 비회원 댓글은 주인이 없으므로 본인 확인이 성립하지 않는다 — 운영자만 지울 수 있다. */
    public boolean isWrittenBy(Long candidateMemberId) {
        return memberId != null && memberId.equals(candidateMemberId);
    }
}
