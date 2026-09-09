package com.woobeee.mvc.blog.api.response;

/** 인기 태그 한 줄 — 글 수 포함 (BLOG-AC-22). */
public record GetTagResponse(Long id, String name, long count) {
}
