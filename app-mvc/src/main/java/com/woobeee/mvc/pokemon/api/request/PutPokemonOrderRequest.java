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
 * <p>금액은 <b>수정 시점 환율</b>로 다시 계산된다. 주문 내용이 바뀌면 이체할 금액도 바뀌므로
 * 옛 환율을 붙들고 있을 이유가 없다 — 새 금액의 근거가 되도록 환율 스냅샷도 함께 갱신한다.
 *
 */
public record PutPokemonOrderRequest(
        @Size(max = 60) String depositorName,
        @Min(0) long donationKrw,
        @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal extraInr,
        @Size(max = 500) String memo,
        @Valid List<PokemonOrderItemRequest> items
) {}
