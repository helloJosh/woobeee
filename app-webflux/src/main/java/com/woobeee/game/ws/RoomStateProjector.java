package com.woobeee.game.ws;

import com.woobeee.game.room.Room;
import com.woobeee.game.ws.payload.ParticipantView;
import com.woobeee.game.ws.payload.RoomStatePayload;

/**
 * 방 상태를 클라이언트에 보낼 모양으로 바꾼다. memberId 는 내보내지 않는다 —
 * 참가자 목록에 회원 내부 식별자를 실을 이유가 없다.
 */
public final class RoomStateProjector {

    private RoomStateProjector() {
    }

    public static RoomStatePayload project(Room room) {
        return new RoomStatePayload(
                room.gameType().name(),
                room.hostParticipantId(),
                room.status().name(),
                room.members().stream()
                        .map(member -> new ParticipantView(
                                member.participant().participantId(),
                                member.participant().displayName(),
                                member.participant().kind().name(),
                                member.ready(),
                                member.connection().name()
                        ))
                        .toList()
        );
    }
}
