package com.woobeee.mvc.pokemon.api.response;

/**
 * 신청서 세부 페이지가 한 번에 받는 것. 계좌와 통화는 차수가 들고 있으므로 차수를 함께 싣는다.
 */
public record PokemonOrderDetailResponse(
        ExchangeRateResponse rate,
        PokemonRoundResponse round,
        PokemonOrderResponse order,
        boolean canManage
) {}
