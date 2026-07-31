package com.woobeee.mvc.auth.api.request;

import com.woobeee.mvc.auth.entity.MemberType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LoginRequest(
        @NotNull(message = "Member type is required")
        MemberType memberType,
        @NotBlank(message = "Device is required")
        String device
) {
}
