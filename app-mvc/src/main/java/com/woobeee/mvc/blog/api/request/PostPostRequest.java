package com.woobeee.mvc.blog.api.request;

import lombok.Builder;

import java.util.List;

@Builder
public record PostPostRequest(
        String titleKo,
        String titleEn,
        Long categoryId,
        /** 태그 이름 목록 — 선택. 정규화 규칙은 TagNormalizer (BLOG-AC-18). */
        List<String> tags
) {
}