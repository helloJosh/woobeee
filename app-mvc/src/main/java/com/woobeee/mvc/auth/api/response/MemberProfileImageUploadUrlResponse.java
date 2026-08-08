package com.woobeee.mvc.auth.api.response;

public record MemberProfileImageUploadUrlResponse(
        String uploadUrl,
        String fileKey,
        long expiresInSeconds
) {
}
