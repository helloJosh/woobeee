package com.woobeee.mvc.pokemon.api.request;

import com.woobeee.mvc.pokemon.entity.PokemonRoundStatus;
import jakarta.validation.constraints.NotNull;

public record PatchPokemonRoundStatusRequest(@NotNull PokemonRoundStatus status) {}
