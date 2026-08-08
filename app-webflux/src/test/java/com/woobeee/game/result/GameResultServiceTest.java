package com.woobeee.game.result;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.reactive.TransactionalOperator;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * <p>The mocked {@link TransactionalOperator} below is stubbed as an identity pass-through
 * ({@code transactional(Mono) -> the same Mono, unchanged}). That proves {@link GameResultService}
 * wires the operator around the right two calls (ordering, argument routing) — it does
 * <strong>not</strong> prove rollback semantics. A real transaction abort on participant-insert
 * failure can only be verified against a live database (integration test), not this unit test.
 */
class GameResultServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:10:00Z");
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);

    private GameResultRepository repository;
    private ReplayUploader uploader;
    private TransactionalOperator transactionalOperator;
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
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(GameResultRepository.class);
        uploader = mock(ReplayUploader.class);
        transactionalOperator = mock(TransactionalOperator.class);
        // Identity pass-through: proves wiring/ordering only, not real commit/rollback semantics.
        when(transactionalOperator.transactional(any(Mono.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new GameResultService(repository, uploader, Clock.fixed(NOW, ZoneOffset.UTC), transactionalOperator);

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

    /** F2: a failure inserting the result row must propagate, not be swallowed. */
    @Test
    void insertResultFailurePropagatesAndSkipsUploadAndAttach() {
        RuntimeException failure = new RuntimeException("insertResult boom");
        when(repository.insertResult(any(FinishedGame.class), eq(NOW))).thenReturn(Mono.error(failure));

        StepVerifier.create(service.record(game(), "{}"))
                .expectErrorMatches(error -> error == failure)
                .verify(VERIFY_TIMEOUT);

        verify(repository).insertResult(any(FinishedGame.class), eq(NOW));
        verify(repository, never()).insertParticipants(anyLong(), any());
        verifyNoInteractions(uploader);
        verify(repository, never()).attachReplayKey(anyLong(), anyString());
    }

    /** F2: a failure inserting participants must propagate, not be swallowed. */
    @Test
    void insertParticipantsFailurePropagatesAndSkipsUploadAndAttach() {
        RuntimeException failure = new RuntimeException("insertParticipants boom");
        when(repository.insertParticipants(eq(77L), any())).thenReturn(Mono.error(failure));

        StepVerifier.create(service.record(game(), "{}"))
                .expectErrorMatches(error -> error == failure)
                .verify(VERIFY_TIMEOUT);

        verify(repository).insertResult(any(FinishedGame.class), eq(NOW));
        verify(repository).insertParticipants(eq(77L), any());
        verifyNoInteractions(uploader);
        verify(repository, never()).attachReplayKey(anyLong(), anyString());
    }

    /**
     * Pins the transaction boundary itself. The other tests would all still pass unchanged if
     * {@code transactional()} wrapped the entire chain (inserts + upload + attach) instead of just
     * the two inserts, because the stub is a transparent identity pass-through regardless of which
     * Mono it receives. This test captures the exact Mono handed to {@code transactional()} and
     * subscribes to it on its own: if the boundary is right, that captured Mono only touches the
     * repository (it re-runs insertResult/insertParticipants) and never the uploader. If the
     * boundary had drifted to include the upload step, this second, independent subscription would
     * also invoke {@code uploader.upload}, which the final assertion catches.
     */
    @Test
    @SuppressWarnings("unchecked")
    void transactionalOperatorWrapsOnlyTheInsertsNotTheUploadOrAttach() {
        when(uploader.upload("OMOK", 77L, "{}")).thenReturn(Mono.just("games/OMOK/77.ndjson"));

        StepVerifier.create(service.record(game(), "{}"))
                .expectNext(77L)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        ArgumentCaptor<Mono<Long>> captor = ArgumentCaptor.forClass(Mono.class);
        verify(transactionalOperator, times(1)).transactional(captor.capture());

        StepVerifier.create(captor.getValue())
                .expectNext(77L)
                .expectComplete()
                .verify(VERIFY_TIMEOUT);

        // repository.insertResult(...) is an eager call made once while record() builds the chain
        // (its Mono, once obtained, is what got captured and re-subscribed), so it stays at 1
        // invocation either way. insertParticipants(...) sits inside a lazy flatMap that fires once
        // per subscription of the upstream insert Mono, so subscribing to the captured Mono a
        // second time re-runs it: 2 invocations total (1 from the original record() call above, 1
        // from subscribing to the captured Mono directly).
        verify(repository, times(1)).insertResult(any(FinishedGame.class), eq(NOW));
        verify(repository, times(2)).insertParticipants(eq(77L), any());
        // The discriminating assertion: the uploader must not have been touched a second time.
        verify(uploader, times(1)).upload("OMOK", 77L, "{}");
    }
}
