package com.woobeee.mvc.pokemon.api.response;

import com.woobeee.mvc.pokemon.entity.PokemonProducts;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 운영자 상품 관리 화면이 보는 한 줄. 신청 화면의 {@link PokemonProductResponse} 와 달리
 * 내려간 상품과 수정 시각까지 보여 준다.
 *
 * @param inUse 신청서에 쓰인 적이 있는가. 있으면 지울 수 없고 내리기만 된다
 */
public record PokemonManagedProductResponse(
        Long id,
        String name,
        String currency,
        BigDecimal price,
        int coins,
        int sortOrder,
        boolean active,
        boolean inUse,
        LocalDateTime updatedAt
) {

    public static PokemonManagedProductResponse of(PokemonProducts product, boolean inUse) {
        return new PokemonManagedProductResponse(
                product.getId(), product.getName(), product.getCurrency(), product.getPrice(),
                product.getCoins(),
                product.getSortOrder(), product.isActive(), inUse, product.getUpdatedAt());
    }
}
