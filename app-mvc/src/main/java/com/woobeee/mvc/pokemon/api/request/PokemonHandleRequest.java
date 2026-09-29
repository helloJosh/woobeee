package com.woobeee.mvc.pokemon.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 주최자 주소 정하기 — {@code /pokemon/{handle}} 로 쓰인다. 회원당 한 번만. */
public record PokemonHandleRequest(@NotBlank @Size(min = 2, max = 30) String handle) {}

