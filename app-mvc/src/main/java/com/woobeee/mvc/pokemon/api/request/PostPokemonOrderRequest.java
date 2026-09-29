package com.woobeee.mvc.pokemon.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param applicantName 비회원만 쓴다. 로그인 신청이면 회원 닉네임이 이기므로 무시된다.
 * @param donationKrw   선택 기부금(원화). 상품값과 별개로 이체액에 더해진다.
 * @param extraInr      상품표에 없는 것을 위한 자유 입력 루피. 생략하면 0 이다.
 * @param items         빈 목록 검사는 <b>서비스가</b> 한다 — {@code @NotEmpty} 를 달면 구체적인
 *                      {@code pokemon_emptyOrder} 가 도달 불가가 되기 때문이다.
 */
public record PostPokemonOrderRequest(
        @Size(max = 60) String applicantName,
        @Size(max = 60) String depositorName,
        @Min(0) long donationKrw,
        @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal extraInr,
        @Size(max = 500) String memo,
        @Valid List<PokemonOrderItemRequest> items
) {}
