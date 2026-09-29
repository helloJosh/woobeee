package com.woobeee.mvc.pokemon.appstore;

import java.math.BigDecimal;

/**
 * App Store 의 In-App Purchases 한 줄을 읽은 것.
 *
 * @param name     스토어에 적힌 이름 그대로 — 동기화의 식별자다
 * @param priceInr 루피 가격
 * @param coins    이름에서 뽑은 포켓코인 수. 코인 상품이 아니면 0
 */
public record ScrapedProduct(String name, BigDecimal priceInr, int coins) {}
