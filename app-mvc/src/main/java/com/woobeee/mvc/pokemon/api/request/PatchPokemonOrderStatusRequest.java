package com.woobeee.mvc.pokemon.api.request;

import com.woobeee.mvc.pokemon.entity.PokemonOrderStatus;
import jakarta.validation.constraints.NotNull;

public record PatchPokemonOrderStatusRequest(@NotNull PokemonOrderStatus status) {}
