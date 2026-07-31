package com.woobeee.mvc.blog.api.response;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record GetPostsResponse(
        boolean hasNext,
        List<PostContent> contents
) {

    public record PostContent(
            Long id,
            String title,
            String content,
            String categoryName,
            Long categoryId,
            Long views,
            Long likes,
            LocalDateTime createdAt
    ) {
    }
}