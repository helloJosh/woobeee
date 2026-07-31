package com.woobeee.mvc.blog.api.request;

import lombok.Builder;

@Builder
public record PostPostRequest(
        String titleKo,
        String titleEn,
        Long categoryId
) {
}