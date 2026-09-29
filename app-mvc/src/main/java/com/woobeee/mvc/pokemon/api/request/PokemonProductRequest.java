package com.woobeee.mvc.pokemon.api.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 상품 등록·수정. 운영자가 인게임 상점을 보고 직접 채운다.
 *
 * @param name     상점에 뜨는 이름 그대로. 같은 이름은 두 번 등록할 수 없다
 * @param coins    포켓코인 수. 패스·티켓처럼 코인이 아닌 상품은 0
 * @param active   내려두면 신청 화면의 목록에서 빠진다. 지우는 대신 이것을 쓴다
 */
public record PokemonProductRequest(
        @NotBlank @Size(max = 200) String name,
        @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal priceInr,
        @Min(0) int coins,
        boolean active
) {}
