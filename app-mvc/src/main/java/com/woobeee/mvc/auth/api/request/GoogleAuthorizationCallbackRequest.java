package com.woobeee.mvc.auth.api.request;

import jakarta.validation.constraints.NotBlank;

public record GoogleAuthorizationCallbackRequest(
        @NotBlank(message = "Authorization code is required")
        String code,
        @NotBlank(message = "State is required")
        String state
) {
}
