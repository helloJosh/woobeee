package com.woobeee.game.api.response;

public record RoomSummaryResponse(
        String gameType,
        String status,
        int capacity,
        int participantCount
) {
}
