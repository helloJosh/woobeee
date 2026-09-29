package com.woobeee.mvc.pokemon.api.response;

import com.woobeee.mvc.pokemon.rate.ExchangeRate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** @param stale 외부 조회가 실패해 마지막으로 성공한 값을 내보내는 중이면 true. */
public record ExchangeRateResponse(BigDecimal inrToKrw, LocalDateTime fetchedAt, boolean stale) {

    public static ExchangeRateResponse from(ExchangeRate rate) {
        return new ExchangeRateResponse(rate.inrToKrw(), rate.fetchedAt(), rate.stale());
    }
}
