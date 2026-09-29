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

    /**
     * 차수를 열 때 자동으로 채워지는 기본 계좌. 차수는 자기 계좌를 따로 들고 있으므로
     * 여기를 고쳐도 이미 연 차수는 바뀌지 않는다 — 그 계좌로 이미 입금한 사람이 있다.
     */
    @Column(length = 200)
    private String bankAccount;

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

    /** 주소는 바꾸지 않는다 — 바꾸면 이전 주소로 공유한 링크가 전부 죽는다. */
    public void updateBankAccount(String bankAccount) {
        this.bankAccount = bankAccount;
    }
}
