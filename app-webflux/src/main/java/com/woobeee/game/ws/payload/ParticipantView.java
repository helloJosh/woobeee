package com.woobeee.game.ws.payload;

public record ParticipantView(
        String participantId,
        String displayName,
        String kind,
        boolean ready,
        String connection
) {
}
