package com.woobeee.mvc.auth.api.request;

import jakarta.validation.constraints.NotBlank;

public record TokenRefreshRequest(
        @NotBlank(message = "Refresh token is required")
        String refreshToken,
        @NotBlank(message = "Device is required")
        String device
) {
}
