package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.ws.RoomHub;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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

        sweeper.sweep();

        assertThat(registry.find("room-1")).isEmpty();
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
}
