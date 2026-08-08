package com.woobeee.mvc.auth.api.request;

import jakarta.validation.constraints.NotBlank;

public record MemberProfileImageRegisterRequest(
        @NotBlank(message = "File key is required")
        String fileKey
) {
}
