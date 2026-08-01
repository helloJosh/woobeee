package com.woobeee.game.api.response;

public record CreateRoomResponse(
        String roomId,
        String inviteCode,
        String gameType
) {
}
