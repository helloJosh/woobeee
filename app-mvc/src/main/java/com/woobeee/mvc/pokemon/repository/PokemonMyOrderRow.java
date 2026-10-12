package com.woobeee.mvc.pokemon.repository;

import java.time.LocalDateTime;

/**
 * {@link PokemonOrderRepository#findMyOrders} 의 한 행. 신청서에 차수·주최자 주소를 붙인 모양이다.
 * 별칭이 곧 getter 이름이다 — 쿼리의 따옴표 친 별칭과 맞아야 한다.
 */
public interface PokemonMyOrderRow {
    Long getOrderId();

    Long getRoundId();

    String getHostHandle();

    Integer getRoundSequence();

    String getRoundTitle();

    String getCurrency();

    String getRoundStatus();

    String getOrderStatus();

    Long getTransferKrw();

    LocalDateTime getCreatedAt();
}
