package com.woobeee.mvc.pokemon.api.response;

import java.util.List;

/**
 * {@code /pokemon} 첫 화면 — 최근에 열린 차수들.
 *
 * @param myHandle    내 주소. 아직 정하지 않았으면 null 이고, 그때 차수를 열려면 먼저 정해야 한다
 * @param currencies  차수를 열 때 고를 수 있는 통화
 */
public record PokemonHomeResponse(
        List<PokemonRoundResponse> rounds,
        String myHandle,
        boolean loggedIn,
        List<String> currencies
) {}
