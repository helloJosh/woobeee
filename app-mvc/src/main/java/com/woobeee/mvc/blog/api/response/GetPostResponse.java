package com.woobeee.mvc.blog.api.response;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record GetPostResponse(
        Long id,
        String title,
        String content,
        String categoryName,
        Long categoryId,
        Long views,
        Long likes,
        Boolean isLiked,
        LocalDateTime createdAt,
        List<TagResponse> tags
) {
}