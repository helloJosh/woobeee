package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.ws.RoomHub;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

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
}
