package com.woobeee.mvc.pokemon.api.response;

import java.util.List;

/**
 * 차수 한 건의 화면이 받는 것 전부 — 차수, 그 차수 통화의 상품표, 신청서 전체(댓글 포함),
 * 지금 환율.
 *
 * @param currentRate 현재 환율. 차수의 {@code quotedRate} 와 비교해 환차손익을 낸다
 */
public record PokemonRoundBoardResponse(
        PokemonRoundResponse round,
        ExchangeRateResponse currentRate,
        List<PokemonProductResponse> products,
        List<PokemonOrderResponse> orders
) {}
