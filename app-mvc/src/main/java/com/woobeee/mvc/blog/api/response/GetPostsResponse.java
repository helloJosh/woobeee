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
            /** locale 의 설명 — 영어가 비면 한국어로 대체. 없으면 null (BLOG-AC-23). */
            String description,
            String content,
            String categoryName,
            Long categoryId,
            Long views,
            Long likes,
            LocalDateTime createdAt,
            List<TagResponse> tags
    ) {
    }
}