package com.woobeee.mvc.pokemon.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 차수 개설.
 *
 * @param currency   ISO 4217. 이 차수의 상품·금액이 모두 이 통화다. 나중에 못 바꾼다
 * @param bankAccount 비우면 주최자 기본 계좌를 쓴다
 */
public record PostPokemonRoundRequest(
        @Size(max = 100) String title,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Size(max = 200) String bankAccount,
        LocalDate deadline,
        @Size(max = 500) String memo
) {}
