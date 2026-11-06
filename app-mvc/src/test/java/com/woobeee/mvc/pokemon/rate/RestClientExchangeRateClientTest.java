package com.woobeee.mvc.pokemon.rate;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** POKEMON-AC-06 — 응답하지 않는 제공자가 스케줄러·요청 스레드를 붙들어 두면 안 된다. */
class RestClientExchangeRateClientTest {

    @Test
    void POKEMON_AC_06_aProviderThatNeverAnswersTimesOutInsteadOfHanging() throws Exception {
        try (ServerSocket silent = new ServerSocket(0)) {
            // 연결은 받아 주되 아무 것도 보내지 않는다.
            Thread acceptor = Thread.startVirtualThread(() -> {
                try {
                    silent.accept();
                    Thread.sleep(10_000);
                } catch (Exception ignored) {
                    // 테스트가 끝나 소켓이 닫히면 여기로 온다
                }
            });
            ExchangeRateProperties properties = new ExchangeRateProperties(
                    "http://localhost:" + silent.getLocalPort() + "/{currency}", null, List.of("INR"));
            RestClientExchangeRateClient client = new RestClientExchangeRateClient(
                    properties, Duration.ofMillis(300), Duration.ofMillis(300));

            long started = System.nanoTime();
            var result = client.fetchToKrw("INR");
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

            assertThat(result).isEmpty();
            assertThat(elapsedMs).isLessThan(3_000);
            acceptor.interrupt();
        }
    }
}
