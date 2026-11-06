package com.woobeee.mvc.pokemon.api.response;

import java.util.List;

/**
 * 차수 한 건의 화면이 받는 것 전부 — 차수, 그 차수 통화의 상품표, 신청서 전체(댓글 포함),
 * 지금 환율.
 *
 * @param currentRate 오늘 환율(하루 한 번 갱신). 신청서의 예상 금액이 이 값으로 계산된다
 */
public record PokemonRoundBoardResponse(
        PokemonRoundResponse round,
        ExchangeRateResponse currentRate,
        List<PokemonProductResponse> products,
        List<PokemonOrderResponse> orders
) {}
