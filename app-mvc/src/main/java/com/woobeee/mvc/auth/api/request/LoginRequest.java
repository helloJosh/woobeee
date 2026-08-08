package com.woobeee.mvc.auth.api.request;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "Device is required")
        String device
) {
}
