package com.woobeee.mvc.pokemon.rate;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param url      INR 기준 환율 JSON 을 주는 엔드포인트
 * @param cacheTtl 신선한 값으로 취급할 기간. 제공자가 하루 한 번 갱신하므로 짧게 잡을 이유가 없다.
 */
@ConfigurationProperties(prefix = "pokemon.exchange-rate")
public record ExchangeRateProperties(String url, Duration cacheTtl) {

    public ExchangeRateProperties {
        if (url == null || url.isBlank()) {
            url = "https://open.er-api.com/v6/latest/INR";
        }
        if (cacheTtl == null) {
            cacheTtl = Duration.ofHours(1);
        }
    }
}
