package com.woobeee.mvc.pokemon.rate;

import java.util.Optional;

/** 외부 환율 제공자. 실패는 예외가 아니라 빈 Optional 로 알린다 — 폴백이 정상 경로이기 때문이다. */
public interface ExchangeRateClient {

    /** {@code currency} 1단위가 몇 원인지. */
    Optional<ExchangeRate> fetchToKrw(String currency);
}
