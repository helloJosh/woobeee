package com.woobeee.game.api.request;

import com.woobeee.game.room.GameType;
import jakarta.validation.constraints.NotNull;

public record CreateRoomRequest(
        @NotNull(message = "Game type is required")
        GameType gameType
) {
}
