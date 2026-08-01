package com.woobeee.game.identity;

public record GuestToken(
        String token,
        String participantId,
        String displayName
) {
}
