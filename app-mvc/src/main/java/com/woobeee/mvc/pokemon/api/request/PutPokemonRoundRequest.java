package com.woobeee.mvc.pokemon.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 차수 수정. 통화는 바꿀 수 없다 — 이미 들어온 신청서가 그 통화의 상품으로 채워져 있다.
 */
public record PutPokemonRoundRequest(
        @Size(max = 100) String title,
        @NotBlank @Size(max = 200) String bankAccount,
        LocalDate deadline,
        @Size(max = 500) String memo
) {}
