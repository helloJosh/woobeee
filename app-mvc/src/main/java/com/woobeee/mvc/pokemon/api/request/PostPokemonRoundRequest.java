package com.woobeee.mvc.pokemon.api.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 차수 개설.
 *
 * @param currency   ISO 4217. 이 차수의 상품·금액이 모두 이 통화다. 나중에 못 바꾼다
 * @param rateMode   FIXED(차수 환율 하나로) 또는 PER_ORDER(신청마다 그때 환율)
 * @param quotedRate  FIXED 일 때 쓸 환율. 비우면 지금 환율을 그대로 쓴다
 * @param bankAccount 비우면 주최자 기본 계좌를 쓴다
 */
public record PostPokemonRoundRequest(
        @Size(max = 100) String title,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotBlank @Size(max = 20) String rateMode,
        @DecimalMin(value = "0.000001") @Digits(integer = 8, fraction = 6) BigDecimal quotedRate,
        @Size(max = 200) String bankAccount,
        LocalDate deadline,
        @Size(max = 500) String memo
) {}
