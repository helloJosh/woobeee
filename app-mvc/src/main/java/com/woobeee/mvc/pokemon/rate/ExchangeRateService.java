package com.woobeee.mvc.pokemon.rate;

import com.woobeee.mvc.pokemon.exception.PokemonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 환율을 Redis 로 감싼다. 통화마다 두 개의 키를 쓴다:
 *
 * <ul>
 *   <li>{@code pokemon:rate:<CUR>} — TTL 이 걸린 신선한 값. 살아 있으면 외부 호출을 하지 않는다
 *   <li>{@code pokemon:rate:<CUR>:last} — TTL 없는 마지막 성공값. 제공자가 죽었을 때의 폴백
 * </ul>
 *
 * <p>폴백은 {@link ExchangeRate#stale()} 로 표시해 화면이 "지난 환율" 임을 드러낼 수 있게 한다.
 * 폴백조차 없으면 {@link PokemonErrorCode#RATE_UNAVAILABLE} — 환율을 모른 채로 신청을 받으면
 * 이체 금액의 근거가 사라지므로 추정값을 만들어 내지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeRateService {
    private static final String KEY_PREFIX = "pokemon:rate:";

    private final StringRedisTemplate redisTemplate;
    private final ExchangeRateClient client;
    private final ExchangeRateProperties properties;

    /** 차수를 열 때 고를 수 있는 통화. */
    public List<String> supportedCurrencies() {
        return properties.currencies();
    }

    public ExchangeRate current(String currency) {
        if (!properties.supports(currency)) {
            throw PokemonErrorCode.UNSUPPORTED_CURRENCY.asException();
        }

        Optional<ExchangeRate> fresh = read(freshKey(currency), currency);
        if (fresh.isPresent()) {
            return fresh.get();
        }

        Optional<ExchangeRate> fetched = client.fetchToKrw(currency);
        if (fetched.isPresent()) {
            cache(fetched.get());
            return fetched.get();
        }

        return read(lastKey(currency), currency)
                .map(ExchangeRate::asStale)
                .orElseThrow(PokemonErrorCode.RATE_UNAVAILABLE::asException);
    }

    private static String freshKey(String currency) {
        return KEY_PREFIX + currency;
    }

    private static String lastKey(String currency) {
        return KEY_PREFIX + currency + ":last";
    }

    private void cache(ExchangeRate rate) {
        String serialized = serialize(rate);
        try {
            redisTemplate.opsForValue().set(freshKey(rate.currency()), serialized, properties.cacheTtl());
            redisTemplate.opsForValue().set(lastKey(rate.currency()), serialized);
        } catch (Exception ex) {
            // Redis 가 없어도 환율 조회 자체는 성공했다 — 캐시는 부가 기능이므로 삼킨다.
            log.warn("could not cache the exchange rate: {}", ex.toString());
        }
    }

    private Optional<ExchangeRate> read(String key, String currency) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key))
                    .flatMap(raw -> deserialize(raw, currency));
        } catch (Exception ex) {
            log.warn("could not read the cached exchange rate: {}", ex.toString());
            return Optional.empty();
        }
    }

    /** {@code <rate>|<epochSecond>} — 값이 두 개뿐이라 JSON 을 끌어들이지 않았다. */
    private static String serialize(ExchangeRate rate) {
        long epochSecond = rate.fetchedAt().atZone(ZoneId.systemDefault()).toEpochSecond();
        return rate.toKrw().toPlainString() + "|" + epochSecond;
    }

    private static Optional<ExchangeRate> deserialize(String raw, String currency) {
        String[] parts = raw.split("\\|", 2);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            BigDecimal rate = new BigDecimal(parts[0]);
            if (rate.signum() <= 0) {
                return Optional.empty();
            }
            LocalDateTime fetchedAt = LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(Long.parseLong(parts[1])), ZoneId.systemDefault());
            return Optional.of(new ExchangeRate(currency, rate, fetchedAt, false));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }
}
