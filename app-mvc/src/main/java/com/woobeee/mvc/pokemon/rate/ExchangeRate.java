package com.woobeee.mvc.pokemon.rate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * INR -> KRW 환율 한 건.
 *
 * @param inrToKrw  1 루피가 몇 원인지
 * @param fetchedAt 이 환율의 기준 시각 (제공자가 알려준 갱신 시각)
 * @param stale     외부 조회가 실패해 마지막으로 성공한 값을 대신 내보낸 경우 true
 */
public record ExchangeRate(BigDecimal inrToKrw, LocalDateTime fetchedAt, boolean stale) {

    public ExchangeRate asStale() {
        return new ExchangeRate(inrToKrw, fetchedAt, true);
    }
}
