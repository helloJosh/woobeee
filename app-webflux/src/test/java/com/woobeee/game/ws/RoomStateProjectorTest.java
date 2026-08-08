package com.woobeee.game.ws;

import com.woobeee.game.identity.GameParticipant;
import com.woobeee.game.room.ConnectionState;
import com.woobeee.game.room.GameType;
import com.woobeee.game.room.Room;
import com.woobeee.game.ws.payload.RoomStatePayload;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RoomStateProjectorTest {

    @Test
    void projectsRoomAndParticipantsInJoinOrder() {
        Room room = new Room("room-1", "code", GameType.DODGE, Instant.parse("2026-08-01T00:00:00Z"),
                GameParticipant.member(11L, "host"));
        room.addMember(GameParticipant.guest("a", "손님"));
        room.setReady("m:11", true);
        room.setConnection("g:a", ConnectionState.DISCONNECTED);

        RoomStatePayload payload = RoomStateProjector.project(room);

        assertThat(payload.gameType()).isEqualTo("DODGE");
        assertThat(payload.hostParticipantId()).isEqualTo("m:11");
        assertThat(payload.status()).isEqualTo("WAITING");
        assertThat(payload.participants()).hasSize(2);

        assertThat(payload.participants().get(0).participantId()).isEqualTo("m:11");
        assertThat(payload.participants().get(0).kind()).isEqualTo("MEMBER");
        assertThat(payload.participants().get(0).ready()).isTrue();
        assertThat(payload.participants().get(0).connection()).isEqualTo("CONNECTED");

        assertThat(payload.participants().get(1).participantId()).isEqualTo("g:a");
        assertThat(payload.participants().get(1).kind()).isEqualTo("GUEST");
        assertThat(payload.participants().get(1).ready()).isFalse();
        assertThat(payload.participants().get(1).connection()).isEqualTo("DISCONNECTED");
    }

    @Test
    void doesNotLeakMemberIds() {
        Room room = new Room("room-1", "code", GameType.OMOK, Instant.parse("2026-08-01T00:00:00Z"),
                GameParticipant.member(11L, "host"));

        RoomStatePayload payload = RoomStateProjector.project(room);

        assertThat(payload.participants().getFirst()).hasNoNullFieldsOrProperties();
        assertThat(ParticipantViewFields.names()).doesNotContain("memberId");
    }

    /** 리플렉션 대신 명시적으로 필드 이름을 적어두어 프로젝션이 커질 때 눈에 띄게 한다. */
    static final class ParticipantViewFields {
        static java.util.List<String> names() {
            return java.util.Arrays.stream(
                            com.woobeee.game.ws.payload.ParticipantView.class.getRecordComponents())
                    .map(java.lang.reflect.RecordComponent::getName)
                    .toList();
        }
    }
}
