package com.woobeee.game.api.request;

import jakarta.validation.constraints.NotBlank;

public record IssueGuestTokenRequest(
        @NotBlank(message = "Invite code is required")
        String inviteCode,
        @NotBlank(message = "Nickname is required")
        String nickname
) {
}
