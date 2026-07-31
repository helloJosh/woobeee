package com.woobeee.game.security;

public record GamePrincipal(
        Long memberId,
        String role,
        String device
) {
}
