package com.woobeee.mvc.pokemon.rate;

import com.woobeee.mvc.pokemon.exception.PokemonErrorCode;
import com.woobeee.mvc.pokemon.exception.PokemonException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** POKEMON-AC-06 — 환율 조회·하루 한 번 갱신·폴백. */
@SuppressWarnings("unchecked")
class ExchangeRateServiceTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final ExchangeRateClient client = mock(ExchangeRateClient.class);
    private final ExchangeRateProperties properties =
            new ExchangeRateProperties(null, null, List.of("INR", "USD"));
    private ExchangeRateService service;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        service = new ExchangeRateService(redis, client, properties);
    }

    private static ExchangeRate rate(String currency, String toKrw) {
        return new ExchangeRate(currency, new BigDecimal(toKrw), LocalDateTime.of(2026, 11, 6, 9, 10), false);
    }

    private static String cached(String toKrw) {
        long epoch = LocalDateTime.of(2026, 11, 5, 9, 10).atZone(ZoneId.systemDefault()).toEpochSecond();
        return toKrw + "|" + epoch;
    }

    @Test
    void POKEMON_AC_06_cacheTtlDefaultsToOneDay() {
        assertThat(new ExchangeRateProperties(null, null, null).cacheTtl()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void POKEMON_AC_06_refreshAll_overwritesFreshValue() {
        when(ops.get("pokemon:rate:INR")).thenReturn(cached("14.00"));
        when(client.fetchToKrw("INR")).thenReturn(Optional.of(rate("INR", "15.00")));
        when(client.fetchToKrw("USD")).thenReturn(Optional.of(rate("USD", "1400.00")));

        service.refreshAll();

        verify(client).fetchToKrw("INR");
        verify(ops).set(eq("pokemon:rate:INR"), any(String.class), eq(Duration.ofHours(24)));
        verify(ops).set(eq("pokemon:rate:USD"), any(String.class), eq(Duration.ofHours(24)));
    }

    @Test
    void POKEMON_AC_06_refreshAll_swallowsProviderFailureAndContinues() {
        when(client.fetchToKrw("INR")).thenThrow(new IllegalStateException("provider down"));
        when(client.fetchToKrw("USD")).thenReturn(Optional.of(rate("USD", "1400.00")));

        assertThatCode(service::refreshAll).doesNotThrowAnyException();

        verify(ops).set(eq("pokemon:rate:USD"), any(String.class), any(Duration.class));
        verify(ops, never()).set(eq("pokemon:rate:INR"), any(String.class), any(Duration.class));
    }

    @Test
    void POKEMON_AC_06_current_fallsBackToLastAsStale() {
        when(client.fetchToKrw("INR")).thenReturn(Optional.empty());
        when(ops.get("pokemon:rate:INR:last")).thenReturn(cached("14.00"));

        ExchangeRate result = service.current("INR");

        assertThat(result.stale()).isTrue();
        assertThat(result.toKrw()).isEqualByComparingTo("14.00");
    }

    @Test
    void POKEMON_AC_06_current_throwsWhenNothingIsKnown() {
        when(client.fetchToKrw("INR")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.current("INR"))
                .isInstanceOfSatisfying(PokemonException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(PokemonErrorCode.RATE_UNAVAILABLE));
    }
}
