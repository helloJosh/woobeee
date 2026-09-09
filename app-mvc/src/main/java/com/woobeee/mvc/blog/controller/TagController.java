package com.woobeee.mvc.blog.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.blog.api.response.GetTagResponse;
import com.woobeee.mvc.blog.service.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 태그는 글쓰기 안에서만 만들어진다 — 여기는 읽기 전용이다 (BLOG-AC-22). */
@RestController
@RequestMapping("/api/back/tags")
@Tag(name = "Tag Controller", description = "게시글 태그 컨트롤러")
@RequiredArgsConstructor
public class TagController {
    private final TagService tagService;

    @GetMapping
    @Operation(summary = "인기 태그 조회", description = "글 수 내림차순 상위 태그를 조회합니다.")
    public ApiResponse<List<GetTagResponse>> getPopularTags(
            @RequestParam(value = "limit", defaultValue = "20") Integer limit) {
        return ApiResponse.success(tagService.getPopular(limit), "Tags retrieved");
    }
}
