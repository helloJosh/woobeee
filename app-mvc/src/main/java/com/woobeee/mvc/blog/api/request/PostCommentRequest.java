package com.woobeee.mvc.blog.api.request;

import lombok.Builder;

@Builder
public record PostCommentRequest(
        Long postId,
        Long parentId,
        String content
) {
}