package com.woobeee.mvc.pokemon.rate;

import com.woobeee.mvc.pokemon.exception.PokemonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * 환율을 Redis 로 감싼다. 두 개의 키를 쓴다:
 *
 * <ul>
 *   <li>{@code FRESH_KEY} — TTL 이 걸린 신선한 값. 살아 있으면 외부 호출을 하지 않는다.
 *   <li>{@code LAST_KEY} — TTL 없는 마지막 성공값. 제공자가 죽었을 때의 폴백이다.
 * </ul>
 *
 * <p>폴백은 {@link ExchangeRate#stale()} 로 표시해 화면이 "지난 환율"임을 드러낼 수 있게 한다.
 * 폴백조차 없으면 {@link PokemonErrorCode#RATE_UNAVAILABLE} — 환율을 모른 채로 신청을 받으면
 * 이체 금액의 근거가 사라지므로 추정값을 만들어 내지 않는다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExchangeRateService {
    private static final String FRESH_KEY = "pokemon:rate:inr-krw";
    private static final String LAST_KEY = "pokemon:rate:inr-krw:last";

    private final StringRedisTemplate redisTemplate;
    private final ExchangeRateClient client;
    private final ExchangeRateProperties properties;

    public ExchangeRate current() {
        Optional<ExchangeRate> fresh = read(FRESH_KEY);
        if (fresh.isPresent()) {
            return fresh.get();
        }

        Optional<ExchangeRate> fetched = client.fetchInrToKrw();
        if (fetched.isPresent()) {
            cache(fetched.get());
            return fetched.get();
        }

        return read(LAST_KEY)
                .map(ExchangeRate::asStale)
                .orElseThrow(PokemonErrorCode.RATE_UNAVAILABLE::asException);
    }

    private void cache(ExchangeRate rate) {
        String serialized = serialize(rate);
        try {
            redisTemplate.opsForValue().set(FRESH_KEY, serialized, properties.cacheTtl());
            redisTemplate.opsForValue().set(LAST_KEY, serialized);
        } catch (Exception ex) {
            // Redis 가 없어도 환율 조회 자체는 성공했다 — 캐시는 부가 기능이므로 삼킨다.
            log.warn("could not cache the exchange rate: {}", ex.toString());
        }
    }

    private Optional<ExchangeRate> read(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key))
                    .flatMap(ExchangeRateService::deserialize);
        } catch (Exception ex) {
            log.warn("could not read the cached exchange rate: {}", ex.toString());
            return Optional.empty();
        }
    }

    /** {@code <rate>|<epochSecond>} — 값이 두 개뿐이라 JSON 을 끌어들이지 않았다. */
    private static String serialize(ExchangeRate rate) {
        long epochSecond = rate.fetchedAt().atZone(ZoneId.systemDefault()).toEpochSecond();
        return rate.inrToKrw().toPlainString() + "|" + epochSecond;
    }

    private static Optional<ExchangeRate> deserialize(String raw) {
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
                    java.time.Instant.ofEpochSecond(Long.parseLong(parts[1])), ZoneId.systemDefault());
            return Optional.of(new ExchangeRate(rate, fetchedAt, false));
        } catch (NumberFormatException | DateTimeParseException ex) {
            return Optional.empty();
        }
    }
}
