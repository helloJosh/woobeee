package com.woobeee.mvc.ai.api.request;

import java.math.BigDecimal;

/** AI 가 제안하는 상품 하나. 통화는 차수를 따라가므로 담지 않는다. */
public record ProductDraft(String name, BigDecimal price, int coins) {
}
