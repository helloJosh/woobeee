package com.woobeee.mvc.blog.api.request;

public record PostPresignedUploadUrlRequest(
        String fileName,
        String contentType,
        String folder
) {
}
