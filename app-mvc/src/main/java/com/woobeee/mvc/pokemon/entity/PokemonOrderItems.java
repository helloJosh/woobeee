package com.woobeee.mvc.pokemon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@Entity
@Table(name = "pokemon_order_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PokemonOrderItems {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long orderId;

    /** 상품표 참조. 상품이 지워지면 끊기므로 표시는 아래 스냅샷으로 한다. */
    private Long productId;

    /** 신청 당시 상품 이름·코인·단가. 상품표가 바뀌어도 과거 금액과 내역이 흔들리지 않게 복사해 둔다. */
    @Column(nullable = false, length = 200)
    private String productName;

    @Column(nullable = false)
    private int coins;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPriceInr;

    @Column(nullable = false)
    private int quantity;

    @Builder
    private PokemonOrderItems(Long orderId, Long productId, String productName, int coins,
                              BigDecimal unitPriceInr, int quantity) {
        this.orderId = orderId;
        this.productId = productId;
        this.productName = productName;
        this.coins = coins;
        this.unitPriceInr = unitPriceInr;
        this.quantity = quantity;
    }

    public static PokemonOrderItems create(Long orderId, PokemonProducts product, int quantity) {
        return PokemonOrderItems.builder()
                .orderId(orderId)
                .productId(product.getId())
                .productName(product.getName())
                .coins(product.getCoins())
                .unitPriceInr(product.getPriceInr())
                .quantity(quantity)
                .build();
    }
}
