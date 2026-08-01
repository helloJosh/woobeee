package com.woobeee.game.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.game.security.GameAuthWebFilter;
import com.woobeee.game.security.ReactiveTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

@WebFluxTest(GameController.class)
@Import(GameAuthWebFilter.class)
class GameControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReactiveTokenVerifier reactiveTokenVerifier;

    @Test
    void healthIsPublic() {
        webTestClient.get().uri("/api/game/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.header.isSuccessful").isEqualTo(true)
                .jsonPath("$.data").isEqualTo("UP");
    }

    @Test
    void meResolvesPrincipalFromSharedRedisToken() {
        when(reactiveTokenVerifier.verify(eq("tok-1")))
                .thenReturn(Mono.just(new TokenMetadata(7L, "ROLE_MEMBER", "ios", "127.0.0.1")));

        webTestClient.get().uri("/api/game/me")
                .header("Authorization", "Bearer tok-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.memberId").isEqualTo(7)
                .jsonPath("$.data.role").isEqualTo("ROLE_MEMBER")
                .jsonPath("$.data.device").isEqualTo("ios");
    }

    @Test
    void meRejectsMissingToken() {
        webTestClient.get().uri("/api/game/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void meRejectsUnknownToken() {
        when(reactiveTokenVerifier.verify(eq("bad-token"))).thenReturn(Mono.empty());

        webTestClient.get().uri("/api/game/me")
                .header("Authorization", "Bearer bad-token")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
