package com.woobeee.mvc.blog.api.request;

import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;

@Builder
public record PostPostRequest(
        String titleKo,
        String titleEn,
        Long categoryId,
        /** 한 줄 설명 — 선택, 각 300자 (BLOG-AC-23). */
        @Size(max = 300) String descriptionKo,
        @Size(max = 300) String descriptionEn,
        /** 태그 이름 목록 — 선택. 정규화 규칙은 TagNormalizer (BLOG-AC-18). */
        List<String> tags
) {
}