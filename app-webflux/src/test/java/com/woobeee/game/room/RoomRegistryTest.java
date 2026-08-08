package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RoomRegistryTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    private final AtomicInteger counter = new AtomicInteger();

    private final GameIdGenerator ids = new GameIdGenerator() {
        @Override
        public String nextRoomId() {
            return "room-" + counter.incrementAndGet();
        }

        @Override
        public String nextInviteCode() {
            return "invite-" + counter.get();
        }

        @Override
        public String nextGuestId() {
            return "guest-" + counter.get();
        }

        @Override
        public int nextSeed() {
            return 42;
        }
    };

    private RoomRegistry registry() {
        return new RoomRegistry(ids, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createStoresRoomAndMakesItFindable() {
        RoomRegistry registry = registry();

        Room room = registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        assertThat(room.roomId()).isEqualTo("room-1");
        assertThat(room.inviteCode()).isEqualTo("invite-1");
        assertThat(registry.find("room-1")).containsSame(room);
    }

    @Test
    void removedRoomIsGone() {
        RoomRegistry registry = registry();
        registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        registry.remove("room-1");

        assertThat(registry.find("room-1")).isEmpty();
    }

    @Test
    void sweepDropsRoomsOlderThanSixHoursAndKeepsTheRest() {
        RoomRegistry registry = registry();
        registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        int removed = registry.sweepExpired(NOW.plusSeconds(6 * 3600 + 1));

        assertThat(removed).isEqualTo(1);
        assertThat(registry.find("room-1")).isEmpty();
    }

    @Test
    void sweepKeepsRoomsInsideTheWindow() {
        RoomRegistry registry = registry();
        registry.create(GameType.OMOK, GameParticipant.member(11L, "host"));

        int removed = registry.sweepExpired(NOW.plusSeconds(3600));

        assertThat(removed).isZero();
        assertThat(registry.find("room-1")).isPresent();
    }
}
