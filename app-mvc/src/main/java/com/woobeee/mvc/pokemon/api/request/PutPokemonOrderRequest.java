package com.woobeee.mvc.pokemon.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 신청서 수정 — 전체 교체다. 담긴 항목이 곧 새 주문 내역이 되고, 빠진 항목은 사라진다.
 *
 * <p>원화는 저장하지 않는다. 항목·기부금·자유 루피가 통째로 바뀌고, 예상 금액은 조회할 때
 * 오늘 환율로 다시 계산된다.
 *
 */
public record PutPokemonOrderRequest(
        @Size(max = 60) String depositorName,
        @Min(0) long donationKrw,
        @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal extraInr,
        @Size(max = 500) String memo,
        @Valid List<PokemonOrderItemRequest> items
) {}
