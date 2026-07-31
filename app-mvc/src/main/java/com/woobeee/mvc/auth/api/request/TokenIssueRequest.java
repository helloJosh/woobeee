package com.woobeee.mvc.auth.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TokenIssueRequest(
        @NotNull(message = "Member ID is required")
        @Positive(message = "Member ID must be positive")
        Long memberId,
        @NotBlank(message = "Role is required")
        String role,
        @NotBlank(message = "Device is required")
        String device
) {
}
