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
 * 신청 화면에 뜨는 상품 한 줄. 운영자가 인게임 상점을 보고 직접 넣고 고친다.
 *
 * <p>패스·티켓은 달마다 바뀌고("GO패스 디럭스: 9월"), App Store 의 IAP 목록에는 그 이름이
 * 뜨지 않아 자동으로 가져올 수 없다. 그래서 코드가 아니라 DB 에 두고 화면에서 관리한다.
 *
 * <p>스토어에서 내려간 상품은 <b>지우지 말고 내린다</b>(active=false). 과거 신청서가
 * 가리키고 있고, 항목에 이름·단가 스냅샷이 남아 있어도 참조는 끊지 않는 편이 낫다.
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

    public static PokemonProducts create(String name, BigDecimal priceInr, int coins,
                                         int sortOrder, LocalDateTime now) {
        return PokemonProducts.builder()
                .name(name).priceInr(priceInr).coins(coins).sortOrder(sortOrder)
                .active(true).firstSeenAt(now).lastSeenAt(now)
                .build();
    }

    public void update(String name, BigDecimal priceInr, int coins, boolean active,
                       LocalDateTime now) {
        this.name = name;
        this.priceInr = priceInr;
        this.coins = coins;
        this.active = active;
        this.lastSeenAt = now;
    }

    public void moveTo(int sortOrder) {
        this.sortOrder = sortOrder;
    }
}
