package com.woobeee.game.room;

import com.woobeee.game.identity.GameParticipant;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RoomTest {

    private Room newRoom() {
        return new Room("room-1", "invite-1", GameType.OMOK, Instant.parse("2026-08-01T00:00:00Z"),
                GameParticipant.member(11L, "host"));
    }

    @Test
    void creatorBecomesHostAndFirstMember() {
        Room room = newRoom();

        assertThat(room.hostParticipantId()).isEqualTo("m:11");
        assertThat(room.members()).hasSize(1);
        assertThat(room.status()).isEqualTo(RoomStatus.WAITING);
        assertThat(room.members().getFirst().connection()).isEqualTo(ConnectionState.CONNECTED);
        assertThat(room.members().getFirst().ready()).isFalse();
    }

    @Test
    void membersKeepJoinOrder() {
        Room room = newRoom();
        room.addMember(GameParticipant.guest("a", "second"));
        room.addMember(GameParticipant.guest("b", "third"));

        assertThat(room.members().stream().map(m -> m.participant().displayName()))
                .containsExactly("host", "second", "third");
    }

    @Test
    void promotingHostPicksTheNextMemberInJoinOrder() {
        Room room = newRoom();
        room.addMember(GameParticipant.guest("a", "second"));
        room.addMember(GameParticipant.guest("b", "third"));

        room.removeMember("m:11");
        room.promoteNextHost();

        assertThat(room.hostParticipantId()).isEqualTo("g:a");
    }

    @Test
    void omokHoldsTwoAndDodgeHoldsEight() {
        assertThat(GameType.OMOK.capacity()).isEqualTo(2);
        assertThat(GameType.DODGE.capacity()).isEqualTo(8);
    }

    @Test
    void connectionAndReadyAreTracked() {
        Room room = newRoom();

        room.setReady("m:11", true);
        room.setConnection("m:11", ConnectionState.DISCONNECTED);

        RoomMember member = room.member("m:11").orElseThrow();
        assertThat(member.ready()).isTrue();
        assertThat(member.connection()).isEqualTo(ConnectionState.DISCONNECTED);
    }
}
