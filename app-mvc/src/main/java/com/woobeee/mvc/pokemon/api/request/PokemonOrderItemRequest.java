package com.woobeee.mvc.pokemon.api.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * @param productId 상품표({@code GET /board} 의 products)의 id. 같은 상품을 여러 번 사는 것은
 *                  수량으로 표현한다 — 같은 id 가 두 줄로 오면 거절한다.
 */
public record PokemonOrderItemRequest(
        @NotNull Long productId,
        @Min(1) @Max(99) int quantity
) {}
