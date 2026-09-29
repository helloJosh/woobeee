package com.woobeee.mvc.pokemon.rate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 어떤 통화 1단위가 몇 원인지.
 *
 * @param currency  ISO 4217 (INR, USD, JPY …)
 * @param toKrw     1 {@code currency} = N KRW
 * @param fetchedAt 이 환율의 기준 시각 (제공자가 알려준 갱신 시각)
 * @param stale     외부 조회가 실패해 마지막으로 성공한 값을 대신 내보내는 경우 true
 */
public record ExchangeRate(String currency, BigDecimal toKrw, LocalDateTime fetchedAt, boolean stale) {

    public ExchangeRate asStale() {
        return new ExchangeRate(currency, toKrw, fetchedAt, true);
    }
}
