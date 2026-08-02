package com.woobeee.game.room;

import com.woobeee.game.dodge.DodgeGameSink;
import com.woobeee.game.dodge.DodgeReplayWriter;
import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.omok.OmokGameSink;
import com.woobeee.game.omok.OmokReplayWriter;
import com.woobeee.game.result.FinishedGame;
import com.woobeee.game.result.GameResultService;
import com.woobeee.game.ws.RoomHub;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomSweeperTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    private GameIdGenerator ids(AtomicInteger counter) {
        return new GameIdGenerator() {
            @Override
            public String nextRoomId() {
                return "room-" + counter.incrementAndGet();
            }

            @Override
            public String nextInviteCode() {
                return "code";
            }

            @Override
            public String nextGuestId() {
                return "guest";
            }

            @Override
            public int nextSeed() {
                return 42;
            }
        };
    }

    @Test
    void sweepRemovesExpiredRoomsAndClosesTheirHubs() {
        AtomicInteger counter = new AtomicInteger();
        RoomRegistry registry = new RoomRegistry(ids(counter), Clock.fixed(NOW, ZoneOffset.UTC));
        registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        RoomHub hub = new RoomHub();
        RoomSweeper sweeper = new RoomSweeper(
                registry, hub, Clock.fixed(NOW.plusSeconds(6 * 3600 + 1), ZoneOffset.UTC));

        // Subscribe before sweeping so we can pin the actual hub-close signal, not just the
        // registry side effect. A sweep that forgets to call roomHub.close(roomId) leaves this
        // flux open forever and the assertion below times out instead of silently passing.
        StepVerifier.create(hub.subscribe("room-1"))
                .then(sweeper::sweep)
                .expectComplete()
                .verify(Duration.ofSeconds(2));

        assertThat(registry.find("room-1")).isEmpty();
    }

    @Test
    void sweepReadsTheClockExactlyOnceSoBothPhasesAgreeOnNow() {
        // A real Clock only moves forward. If sweep() calls clock.instant() twice, a room whose
        // createdAt lands between the two reads is invisible to expiredRoomIds() (so its hub is
        // never closed) but is still deleted by sweepExpired() (which reads the later, larger
        // instant) -- a permanent leak of that room's Sinks.Many. Pin a single read instead.
        AtomicInteger counter = new AtomicInteger();
        RoomRegistry registry = new RoomRegistry(ids(counter), Clock.fixed(NOW, ZoneOffset.UTC));
        registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        AtomicInteger clockReads = new AtomicInteger();
        Instant expiredNow = NOW.plusSeconds(6 * 3600 + 1);
        Clock countingClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                clockReads.incrementAndGet();
                return expiredNow;
            }
        };

        RoomSweeper sweeper = new RoomSweeper(registry, new RoomHub(), countingClock);

        sweeper.sweep();

        assertThat(clockReads.get()).isEqualTo(1);
    }

    @Test
    void sweepKeepsFreshRooms() {
        AtomicInteger counter = new AtomicInteger();
        RoomRegistry registry = new RoomRegistry(ids(counter), Clock.fixed(NOW, ZoneOffset.UTC));
        registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        RoomSweeper sweeper = new RoomSweeper(
                registry, new RoomHub(), Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC));

        sweeper.sweep();

        assertThat(registry.find("room-1")).isPresent();
    }

    @Test
    void aThrowingSweepDoesNotStopTheNextTick() {
        // The interval Flux subscribes once, for the life of the process. If a sweep() throws
        // and that exception reaches the Flux, the sequence terminates with onError and nothing
        // ever resubscribes -- TTL reclamation is silently dead until restart. onTick() is what
        // Flux.interval(...).subscribe(...) calls on every tick; it must swallow a throwing
        // sweep so the next tick still runs.
        AtomicInteger counter = new AtomicInteger();
        AtomicBoolean throwOnce = new AtomicBoolean(true);
        RoomRegistry registry = new RoomRegistry(ids(counter), Clock.fixed(NOW, ZoneOffset.UTC)) {
            @Override
            public List<String> expiredRoomIds(Instant now) {
                if (throwOnce.getAndSet(false)) {
                    throw new IllegalStateException("boom");
                }
                return super.expiredRoomIds(now);
            }
        };
        registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        RoomSweeper sweeper = new RoomSweeper(
                registry, new RoomHub(), Clock.fixed(NOW.plusSeconds(6 * 3600 + 1), ZoneOffset.UTC));

        assertThatCode(sweeper::onTick).doesNotThrowAnyException();
        sweeper.onTick();

        assertThat(registry.find("room-1")).isEmpty();
    }

    /**
     * G2 known gap: {@code sweep()} reclaims a TTL-expired room straight through
     * {@link RoomRegistry#sweepExpired(Instant)} and {@link RoomHub#close(String)} — it never
     * goes through {@link com.woobeee.game.ws.RoomCommandDispatcher#settle}, which is the only
     * path that tells a {@link com.woobeee.game.ws.GameCommandSink} a participant (and therefore,
     * for the last participant, the room) is gone. So an {@link OmokGameSink} that has an active
     * game in the swept room is never told; its per-room maps ({@code games}, {@code startedAt},
     * {@code displayNames}, {@code memberIds}, all keyed by roomId) keep that game forever, even
     * though the room itself, and its hub, are gone.
     */
    @Tag("known-gap")
    @DisplayName("G2: sweeping an expired room does not release the omok sink's game state for it")
    @Test
    void sweepingAnExpiredRoomLeavesTheOmokSinkStillHoldingItsGameState() {
        AtomicInteger counter = new AtomicInteger();
        RoomRegistry registry = new RoomRegistry(ids(counter), Clock.fixed(NOW, ZoneOffset.UTC));
        Room room = registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));
        room.addMember(GameParticipant.guest("a", "손님"));

        RoomHub hub = new RoomHub();
        OmokGameSink sink = new OmokGameSink(
                hub,
                mock(GameResultService.class),
                new OmokReplayWriter(new ObjectMapper()),
                Clock.fixed(NOW, ZoneOffset.UTC));
        sink.onStart(room);
        assertThat(sink.gameOf("room-1"))
                .as("setup: the omok game must actually be running before we sweep the room")
                .isNotNull();

        RoomSweeper sweeper = new RoomSweeper(
                registry, hub, Clock.fixed(NOW.plusSeconds(6 * 3600 + 1), ZoneOffset.UTC));

        sweeper.sweep();

        assertThat(registry.find("room-1"))
                .as("setup: the room really was reclaimed by the sweep")
                .isEmpty();
        assertThat(sink.gameOf("room-1"))
                .as("RoomSweeper must tell the sink the room is gone (e.g. by routing through "
                        + "RoomCommandDispatcher.settle) instead of only dropping it from the registry")
                .isNull();
    }

    /**
     * G2 known gap, dodge escalation. The omok case above is an inert leak: {@link OmokGameSink}
     * is purely reactive to commands, so a swept room's game state just sits there, referenced by
     * nobody, until the process restarts.
     *
     * <p>{@link DodgeGameSink} is different in kind, because it owns a {@code Flux.interval} tick
     * timer per room. The sweep does not go through
     * {@link com.woobeee.game.ws.RoomCommandDispatcher#settle}, so nothing disposes that timer —
     * it keeps advancing the {@link com.woobeee.game.dodge.DodgeGame} of a room that no longer
     * exists, keeps broadcasting into a closed hub, and then, when that phantom game reaches its
     * natural end, calls {@link GameResultService#record}: a {@code game_results} row (plus its
     * participant rows, plus an S3 replay object) written for a room that was reclaimed minutes
     * earlier. That is not a silent leak, it is active pollution of durable storage.
     *
     * <p>The assertion is therefore about the recorded result, not about a leftover map entry:
     * after the sweep, no matter how much time passes, nothing about this room may reach the
     * result service. Today it does, so this test fails — and fixing the gap (settling the game
     * during the sweep, which disposes the timer) is what makes it pass.
     *
     * <p>Neither player ever moves, so a falling obstacle reaches the bottom row and ends the
     * game well inside the 10 virtual seconds advanced below: spawn probability is 0.15 per
     * column per tick and an obstacle needs 15 ticks to fall from row 0 to row 15.
     */
    @Tag("known-gap")
    @DisplayName("G2 (dodge): a swept room's tick timer keeps running and records a result for a room that no longer exists")
    @Test
    void sweepingAnExpiredRoomLeavesTheDodgeTimerRunningUntilItRecordsAPhantomResult() {
        AtomicInteger counter = new AtomicInteger();
        RoomRegistry registry = new RoomRegistry(ids(counter), Clock.fixed(NOW, ZoneOffset.UTC));
        Room room = registry.create(GameType.DODGE, GameParticipant.member(11L, "host"));
        room.addMember(GameParticipant.guest("a", "손님"));

        RoomHub hub = new RoomHub();
        GameResultService resultService = mock(GameResultService.class);
        when(resultService.record(any(FinishedGame.class), any())).thenReturn(Mono.just(7L));
        VirtualTimeScheduler ticks = VirtualTimeScheduler.create();

        DodgeGameSink sink = new DodgeGameSink(
                hub,
                resultService,
                new DodgeReplayWriter(new ObjectMapper()),
                ids(counter),
                Clock.fixed(NOW, ZoneOffset.UTC),
                ticks);
        sink.onStart(room);

        // Setup: prove the tick timer is genuinely live before the sweep, so a later "no result
        // was recorded" cannot pass for the trivial reason that nothing was ever running.
        StepVerifier.create(hub.subscribe("room-1").take(1))
                .then(() -> ticks.advanceTimeBy(Duration.ofMillis(100)))
                .assertNext(message -> assertThat(message.type()).isEqualTo("DODGE_TICK"))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        RoomSweeper sweeper = new RoomSweeper(
                registry, hub, Clock.fixed(NOW.plusSeconds(6 * 3600 + 1), ZoneOffset.UTC));

        sweeper.sweep();

        assertThat(registry.find("room-1"))
                .as("setup: the room really was reclaimed by the sweep")
                .isEmpty();

        // 10 virtual seconds = 100 ticks. The phantom game runs to completion in here.
        ticks.advanceTimeBy(Duration.ofSeconds(10));

        verify(resultService, never()).record(any(FinishedGame.class), any());
    }
}
