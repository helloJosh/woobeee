package com.woobeee.mvc.pokemon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 공동구매를 여는 사람의 URL 조각. {@code /pokemon/{handle}} 로 그 사람의 차수들이 모인다.
 *
 * <p>회원당 하나이고, 바꾸면 이전 주소가 죽으므로 처음 한 번만 정하게 한다.
 * members 에 컬럼을 붙이지 않은 이유는 auth 도메인을 pokemon 사정으로 넓히지 않기 위해서다.
 */
@Getter
@Entity
@Table(name = "pokemon_hosts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PokemonHosts {
    @Id
    private Long memberId;

    /** 소문자·숫자·하이픈만. URL 에 그대로 들어간다. */
    @Column(nullable = false, length = 30, unique = true)
    private String handle;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    private PokemonHosts(Long memberId, String handle) {
        this.memberId = memberId;
        this.handle = handle;
    }

    public static PokemonHosts claim(Long memberId, String handle) {
        return PokemonHosts.builder().memberId(memberId).handle(handle).build();
    }
}
