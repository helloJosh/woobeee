package com.woobeee.mvc.blog.api.response;

/** 글에 붙은 태그 한 개 — 목록·상세 공통 (BLOG-AC-20). 프론트 lib/types.ts 의 Tag 와 1:1. */
public record TagResponse(Long id, String name) {
}
