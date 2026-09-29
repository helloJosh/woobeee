package com.woobeee.mvc.pokemon.api.response;

import com.woobeee.mvc.pokemon.entity.PokemonProducts;

import java.math.BigDecimal;

/** @param coins 포켓코인 수. 이벤트 티켓처럼 코인이 아닌 상품은 0 이다. */
public record PokemonProductResponse(Long id, String name, int coins, BigDecimal priceInr) {

    public static PokemonProductResponse from(PokemonProducts product) {
        return new PokemonProductResponse(
                product.getId(), product.getName(), product.getCoins(), product.getPriceInr());
    }
}
