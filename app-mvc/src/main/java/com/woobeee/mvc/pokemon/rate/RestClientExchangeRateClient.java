package com.woobeee.mvc.pokemon.rate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

/**
 * open.er-api.com 은 키가 필요 없지만 <b>하루 한 번</b>만 갱신한다. 초 단위 실시간이 아니다 —
 * 어차피 실제 청구액은 카드사 환율과 해외결제 수수료가 붙어 달라지므로, 이 값은 "이체할 금액을
 * 정하는 기준"이지 정산가가 아니다.
 */
@Component
@Slf4j
public class RestClientExchangeRateClient implements ExchangeRateClient {
    private static final String KRW = "KRW";

    private final RestClient restClient = RestClient.create();
    private final ExchangeRateProperties properties;

    public RestClientExchangeRateClient(ExchangeRateProperties properties) {
        this.properties = properties;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<ExchangeRate> fetchInrToKrw() {
        try {
            Map<String, Object> body = restClient.get()
                    .uri(properties.url())
                    .retrieve()
                    .body(Map.class);
            if (body == null) {
                return Optional.empty();
            }

            Object rates = body.get("rates");
            if (!(rates instanceof Map<?, ?> rateMap)) {
                return Optional.empty();
            }
            Object krw = rateMap.get(KRW);
            if (!(krw instanceof Number rate) || rate.doubleValue() <= 0) {
                return Optional.empty();
            }

            return Optional.of(new ExchangeRate(
                    BigDecimal.valueOf(rate.doubleValue()),
                    updatedAt(body.get("time_last_update_unix")),
                    false));
        } catch (Exception ex) {
            // 환율 제공자가 죽어도 신청 페이지는 마지막 값으로 계속 돌아야 한다.
            log.warn("exchange rate lookup failed: {}", ex.toString());
            return Optional.empty();
        }
    }

    private LocalDateTime updatedAt(Object epochSeconds) {
        if (epochSeconds instanceof Number seconds && seconds.longValue() > 0) {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds.longValue()), ZoneId.systemDefault());
        }
        return LocalDateTime.now();
    }
}
