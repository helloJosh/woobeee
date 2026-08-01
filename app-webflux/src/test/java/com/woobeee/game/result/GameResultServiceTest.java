package com.woobeee.game.result;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameResultServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:10:00Z");
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

    private GameResultRepository repository;
    private ReplayUploader uploader;
    private GameResultService service;

    private FinishedGame game() {
        return new FinishedGame(
                "OMOK",
                "room-1",
                Instant.parse("2026-08-01T00:00:00Z"),
                NOW,
                "m:11",
                List.of(
                        new FinishedParticipant("m:11", "host", 11L, 1),
                        new FinishedParticipant("g:a", "손님", null, 2)
                )
        );
    }

    @BeforeEach
    void setUp() {
        repository = mock(GameResultRepository.class);
        uploader = mock(ReplayUploader.class);
        service = new GameResultService(repository, uploader, Clock.fixed(NOW, ZoneOffset.UTC));

        when(repository.insertResult(any(FinishedGame.class), eq(NOW))).thenReturn(Mono.just(77L));
        when(repository.insertParticipants(eq(77L), any())).thenReturn(Mono.empty());
        when(repository.attachReplayKey(anyLong(), anyString())).thenReturn(Mono.empty());
    }

    /** GAME-AC-20 */
    @Test
    void recordsTheRowParticipantsAndReplayInOrder() {
        when(uploader.upload("OMOK", 77L, "{}")).thenReturn(Mono.just("games/OMOK/77.ndjson"));

        StepVerifier.create(service.record(game(), "{}"))
                .expectNext(77L)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        InOrder order = inOrder(repository, uploader);
        order.verify(repository).insertResult(any(FinishedGame.class), eq(NOW));
        order.verify(repository).insertParticipants(eq(77L), any());
        order.verify(uploader).upload("OMOK", 77L, "{}");
        order.verify(repository).attachReplayKey(77L, "games/OMOK/77.ndjson");
    }

    /** GAME-AC-21 */
    @Test
    void aFailedUploadStillLeavesTheResultAndSkipsTheKeyUpdate() {
        when(uploader.upload("OMOK", 77L, "{}")).thenReturn(Mono.empty());

        StepVerifier.create(service.record(game(), "{}"))
                .expectNext(77L)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        verify(repository).insertResult(any(FinishedGame.class), eq(NOW));
        verify(repository).insertParticipants(eq(77L), any());
        verify(repository, never()).attachReplayKey(anyLong(), anyString());
    }
}
