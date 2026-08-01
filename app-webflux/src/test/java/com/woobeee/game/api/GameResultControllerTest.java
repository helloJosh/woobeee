package com.woobeee.game.api;

import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.game.api.response.GameResultSummaryResponse;
import com.woobeee.game.result.GameResultQueryRepository;
import com.woobeee.game.result.ReplayUploader;
import com.woobeee.game.security.GameAuthWebFilter;
import com.woobeee.game.security.ReactiveTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@WebFluxTest(GameResultController.class)
@Import(GameAuthWebFilter.class)
class GameResultControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ReactiveTokenVerifier reactiveTokenVerifier;

    @MockitoBean
    private GameResultQueryRepository queryRepository;

    @MockitoBean
    private ReplayUploader replayUploader;

    @BeforeEach
    void setUp() {
        when(reactiveTokenVerifier.verify(eq("tok-1")))
                .thenReturn(Mono.just(new TokenMetadata(11L, "ROLE_MEMBER", "web", "127.0.0.1")));
    }

    @Test
    void listsMyResults() {
        when(queryRepository.findByMemberId(eq(11L), anyInt(), anyInt())).thenReturn(Flux.just(
                new GameResultSummaryResponse(77L, "OMOK", "2026-08-01T00:10:00", 1, "host", true)
        ));

        webTestClient.get().uri("/api/game/me/results")
                .header("Authorization", "Bearer tok-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].gameResultId").isEqualTo(77)
                .jsonPath("$.data[0].gameType").isEqualTo("OMOK")
                .jsonPath("$.data[0].finishRank").isEqualTo(1)
                .jsonPath("$.data[0].replayAvailable").isEqualTo(true);
    }

    @Test
    void listingRequiresAMemberToken() {
        webTestClient.get().uri("/api/game/me/results")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void replayRequiresAMemberToken() {
        webTestClient.get().uri("/api/game/results/77/replay")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    /** GAME-AC-22 */
    @Test
    void replayIsIssuedToAParticipant() {
        when(queryRepository.findReplayAccess(77L, 11L))
                .thenReturn(Mono.just(new GameResultQueryRepository.ReplayAccess("games/OMOK/77.ndjson")));
        when(replayUploader.presignedDownloadUrl("games/OMOK/77.ndjson"))
                .thenReturn("https://s3.example.com/get");

        webTestClient.get().uri("/api/game/results/77/replay")
                .header("Authorization", "Bearer tok-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.replayUrl").isEqualTo("https://s3.example.com/get");
    }

    /** GAME-AC-22 */
    @Test
    void replayIsForbiddenForANonParticipant() {
        when(queryRepository.findReplayAccess(anyLong(), anyLong())).thenReturn(Mono.empty());

        webTestClient.get().uri("/api/game/results/77/replay")
                .header("Authorization", "Bearer tok-1")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void replayIsNotFoundWhenTheUploadNeverLanded() {
        when(queryRepository.findReplayAccess(77L, 11L))
                .thenReturn(Mono.just(new GameResultQueryRepository.ReplayAccess(null)));

        webTestClient.get().uri("/api/game/results/77/replay")
                .header("Authorization", "Bearer tok-1")
                .exchange()
                .expectStatus().isNotFound();
    }
}
