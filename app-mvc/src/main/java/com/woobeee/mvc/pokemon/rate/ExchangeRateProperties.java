package com.woobeee.mvc.pokemon.rate;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * @param urlTemplate  {@code {currency}} 자리에 ISO 4217 코드가 들어가는 주소
 * @param cacheTtl     신선한 값으로 취급할 기간. 제공자가 하루 한 번 갱신하고 우리도 매일 09:10 KST 에 갱신한다
 * @param currencies   차수를 열 때 고를 수 있는 통화. 늘리려면 여기에 추가한다
 */
@ConfigurationProperties(prefix = "pokemon.exchange-rate")
public record ExchangeRateProperties(String urlTemplate, Duration cacheTtl, List<String> currencies) {

    public ExchangeRateProperties {
        if (urlTemplate == null || urlTemplate.isBlank()) {
            urlTemplate = "https://open.er-api.com/v6/latest/{currency}";
        }
        if (cacheTtl == null) {
            cacheTtl = Duration.ofHours(24);
        }
        if (currencies == null || currencies.isEmpty()) {
            currencies = List.of("INR", "USD", "JPY");
        }
    }

    public String urlFor(String currency) {
        return urlTemplate.replace("{currency}", currency);
    }

    public boolean supports(String currency) {
        return currency != null && currencies.contains(currency);
    }
}
