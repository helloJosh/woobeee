package com.woobeee.mvc.pokemon.api.response;

import java.util.List;

/**
 * {@code /pokemon} 첫 화면.
 *
 * <p>차수 목록을 싣지 않는다. 공동구매는 주최자 주소({@code /pokemon/{handle}})로 찾아
 * 들어가는 것이라, 남이 연 것까지 늘어놓을 이유가 없다.
 *
 * @param myHandle     내 주소. 아직 정하지 않았으면 null 이고, 그때 차수를 열려면 먼저 정해야 한다
 * @param myBankAccount 내 기본 입금 계좌 — 차수를 열 때 자동으로 채운다
 * @param currencies   차수를 열 때 고를 수 있는 통화
 */
public record PokemonHomeResponse(
        String myHandle,
        String myBankAccount,
        boolean loggedIn,
        List<String> currencies
) {}
