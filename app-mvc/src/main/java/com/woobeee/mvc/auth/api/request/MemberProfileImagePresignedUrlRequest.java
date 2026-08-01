package com.woobeee.mvc.auth.api.request;

import jakarta.validation.constraints.NotBlank;

public record MemberProfileImagePresignedUrlRequest(
        @NotBlank(message = "File name is required")
        String fileName,
        @NotBlank(message = "Content type is required")
        String contentType
) {
}
