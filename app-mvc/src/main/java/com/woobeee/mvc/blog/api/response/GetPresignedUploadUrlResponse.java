package com.woobeee.mvc.blog.api.response;

public record GetPresignedUploadUrlResponse(
        String uploadUrl,
        String objectKey,
        Long expiresInSeconds
) {
}
