package com.woobeee.mvc.pokemon.api.response;

import com.woobeee.mvc.pokemon.entity.PokemonOrderItems;

import java.math.BigDecimal;

public record PokemonOrderItemResponse(
        Long productId,
        String productName,
        int coins,
        BigDecimal unitPriceInr,
        int quantity
) {

    public static PokemonOrderItemResponse from(PokemonOrderItems item) {
        return new PokemonOrderItemResponse(
                item.getProductId(), item.getProductName(), item.getCoins(),
                item.getUnitPriceInr(), item.getQuantity());
    }
}
