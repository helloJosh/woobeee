package com.woobeee.mvc.pokemon.api.request;

import jakarta.validation.constraints.Size;

/**
 * 주최자 설정. 지금은 기본 입금 계좌뿐이다.
 *
 * <p>주소({@code handle})는 여기서 바꾸지 않는다 — 바꾸면 이전 주소로 공유한 링크가 전부 죽는다.
 */
public record PokemonHostSettingsRequest(@Size(max = 200) String bankAccount) {}
