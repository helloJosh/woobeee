package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.exception.CustomBadRequestException;
import com.woobeee.mvc.blog.exception.ErrorCode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * BLOG-AC-18 — 태그 입력 정규화. trim → 빈 값 제거 → 대소문자 무시 중복 제거(처음 표기 유지) →
 * 개수·길이 상한. 프론트 에디터의 규칙과 같아야 한다.
 */
public final class TagNormalizer {
    public static final int MAX_TAGS = 10;
    public static final int MAX_LENGTH = 30;

    private TagNormalizer() {
    }

    public static List<String> normalize(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            String name = item.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (name.length() > MAX_LENGTH) {
                throw new CustomBadRequestException(ErrorCode.post_invalidTags);
            }
            if (seen.add(name.toLowerCase())) {
                out.add(name);
            }
        }
        if (out.size() > MAX_TAGS) {
            throw new CustomBadRequestException(ErrorCode.post_invalidTags);
        }
        return out;
    }
}
