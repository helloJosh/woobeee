package com.woobeee.mvc.pokemon.appstore;

import java.util.Optional;

/** App Store 제품 페이지를 가져온다. 실패는 예외가 아니라 빈 Optional 이다 — 동기화는 건너뛰면 된다. */
public interface AppStoreIapClient {
    Optional<String> fetchProductPage();
}
