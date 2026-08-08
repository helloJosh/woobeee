package com.woobeee.game.api.response;

public record GuestTokenResponse(
        String token,
        String participantId,
        String displayName
) {
}
