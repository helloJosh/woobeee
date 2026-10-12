package com.woobeee.mvc.pokemon.api.response;

import com.woobeee.mvc.pokemon.repository.PokemonMyOrderRow;

import java.time.LocalDateTime;

/**
 * 마이페이지의 "내가 참가한 공동구매" 한 줄. 어느 차수(주최자 주소 + 차수 번호)에 낸 신청서인지,
 * 차수와 신청서가 각각 어디까지 왔는지, 이체할 금액이 얼마인지만 싣는다. 항목·댓글은 신청서
 * 상세(`/orders/{id}`)가 준다 — 목록이 무거워지지 않게.
 */
public record PokemonMyOrderResponse(
        Long orderId,
        Long roundId,
        String hostHandle,
        int roundSequence,
        String roundTitle,
        String currency,
        String roundStatus,
        String orderStatus,
        long transferKrw,
        LocalDateTime createdAt
) {
    public static PokemonMyOrderResponse of(PokemonMyOrderRow row) {
        return new PokemonMyOrderResponse(
                row.getOrderId(),
                row.getRoundId(),
                row.getHostHandle(),
                row.getRoundSequence(),
                row.getRoundTitle(),
                row.getCurrency(),
                row.getRoundStatus(),
                row.getOrderStatus(),
                row.getTransferKrw(),
                row.getCreatedAt());
    }
}
