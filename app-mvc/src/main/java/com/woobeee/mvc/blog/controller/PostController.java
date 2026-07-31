package com.woobeee.mvc.blog.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.blog.api.request.PostPostRequest;
import com.woobeee.mvc.blog.api.response.GetPostResponse;
import com.woobeee.mvc.blog.api.response.GetPostsResponse;
import com.woobeee.mvc.blog.service.PostService;
import com.woobeee.mvc.blog.support.CustomPageable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/back/posts")
@Tag(name = "Post Controller", description = "게시글 컨트롤러")
@RequiredArgsConstructor
public class PostController {
    private final PostService postService;

    @GetMapping
    @Operation(summary = "전체 게시글 조회 API", description = "검색어, 카테고리, 페이지 조건으로 게시글 목록을 조회합니다.")
    public ApiResponse<GetPostsResponse> getPosts(
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "categoryId", required = false) Long categoryId,
            @RequestParam(value = "page", defaultValue = "0") Integer page,
            @RequestParam(value = "size", defaultValue = "5") Integer size,
            @RequestHeader(name = "loginId", required = false) String loginId,
            @RequestHeader(name = "Accept-Language", defaultValue = "ko-KR") String locale
    ) {
        GetPostsResponse response = postService.getAllPost(q, locale, categoryId, new CustomPageable(page, size));
        return ApiResponse.success(response, "Posts retrieved");
    }

    @Operation(summary = "게시글 조회 API", description = "게시글 상세 정보를 조회합니다.")
    @GetMapping("/{postId}")
    public ApiResponse<GetPostResponse> getPost(
            @PathVariable("postId") Long postId,
            @RequestHeader(name = "loginId", required = false) String loginId,
            @RequestHeader(name = "Accept-Language", defaultValue = "ko-KR") String locale,
            HttpServletRequest request
    ) {
        GetPostResponse response = postService.getPost(postId, locale, loginId, request);
        return ApiResponse.success(response, "Post retrieved");
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "게시글 저장 API", description = "게시글 본문과 첨부 파일을 저장합니다.")
    public ApiResponse<Void> savePost(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestPart("request") PostPostRequest request,
            @RequestPart(value = "markdownEn", required = false) MultipartFile markdownEn,
            @RequestPart(value = "markdownKr", required = false) MultipartFile markdownKr,
            @RequestPart(value = "file", required = false) List<MultipartFile> files
    ) {
        postService.savePost(request, loginId, markdownEn, markdownKr, files);
        return ApiResponse.createSuccess("Post created");
    }

    @Operation(summary = "게시글 삭제 API", description = "게시글을 삭제합니다.")
    @DeleteMapping("/{postId}")
    public ApiResponse<Void> deletePost(
            @PathVariable(value = "postId") Long postId,
            @RequestHeader(name = "loginId", required = false) String loginId
    ) {
        postService.deletePost(postId, loginId);
        return ApiResponse.success("Post deleted");
    }
}
