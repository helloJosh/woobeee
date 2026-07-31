package com.woobeee.mvc.blog.api.request;

import lombok.Builder;

@Builder
public record PostCategoryRequest(
        String nameKo,
        String nameEn
) {
}