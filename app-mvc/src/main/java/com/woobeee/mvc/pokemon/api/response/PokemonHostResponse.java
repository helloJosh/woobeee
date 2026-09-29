package com.woobeee.mvc.pokemon.api.response;

import java.util.List;

/** {@code /pokemon/{handle}} — 한 주최자와 그가 연 차수들. */
public record PokemonHostResponse(
        String handle,
        String name,
        boolean isMe,
        List<PokemonRoundResponse> rounds
) {}
