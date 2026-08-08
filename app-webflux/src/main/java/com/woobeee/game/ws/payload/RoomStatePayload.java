package com.woobeee.game.ws.payload;

import java.util.List;

public record RoomStatePayload(
        String gameType,
        String hostParticipantId,
        String status,
        List<ParticipantView> participants
) {
}
