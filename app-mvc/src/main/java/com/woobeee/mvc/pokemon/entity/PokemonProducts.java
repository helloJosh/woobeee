package com.woobeee.mvc.pokemon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * App Store 의 In-App Purchases 한 줄. 매일 동기화되므로 코드가 아니라 DB 에 산다.
 *
 * <p>식별자는 <b>이름</b>이다 — 스토어 페이지가 상품 id 를 내주지 않는다. 이름이 바뀌면
 * 새 상품으로 들어오고 옛 이름은 내려간다(active=false). 과거 신청서는 이름을 스냅샷으로
 * 들고 있으므로 그래도 읽힌다.
 */
@Getter
@Entity
@Table(name = "pokemon_products")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PokemonProducts {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200, unique = true)
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal priceInr;

    /** 이름에서 뽑은 포켓코인 수. 이벤트 티켓처럼 코인이 아닌 상품은 0. */
    @Column(nullable = false)
    private int coins;

    @Column(nullable = false)
    private int sortOrder;

    /** 스토어 목록에서 사라지면 false 로 내린다 — 지우지 않는다. */
    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private LocalDateTime firstSeenAt;

    @Column(nullable = false)
    private LocalDateTime lastSeenAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    private PokemonProducts(String name, BigDecimal priceInr, int coins, int sortOrder,
                            boolean active, LocalDateTime firstSeenAt, LocalDateTime lastSeenAt) {
        this.name = name;
        this.priceInr = priceInr;
        this.coins = coins;
        this.sortOrder = sortOrder;
        this.active = active;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
    }

    public static PokemonProducts seen(String name, BigDecimal priceInr, int coins,
                                       int sortOrder, LocalDateTime now) {
        return PokemonProducts.builder()
                .name(name).priceInr(priceInr).coins(coins).sortOrder(sortOrder)
                .active(true).firstSeenAt(now).lastSeenAt(now)
                .build();
    }

    /** 스토어에서 다시 본 상품 — 값을 맞추고 다시 올린다. */
    public void refresh(BigDecimal priceInr, int coins, int sortOrder, LocalDateTime now) {
        this.priceInr = priceInr;
        this.coins = coins;
        this.sortOrder = sortOrder;
        this.active = true;
        this.lastSeenAt = now;
    }

    /** 이번 목록에 없던 상품 — 내리기만 한다. */
    public void retire() {
        this.active = false;
    }
}
