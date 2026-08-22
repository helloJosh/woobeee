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
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
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

    /**
     * 본문 이미지 스트리밍. 버킷을 공개하지 않고 앱이 자격증명으로 대신 읽어 준다 --
     * 공개 URL 방식과 달리 프록시 규칙이나 버킷 익명 읽기 같은 인프라 작업이 필요 없다.
     *
     * <p>{@code ApiResponse} 봉투를 태우지 않는다. {@code <img>} 가 여는 주소이므로 바이트와
     * contentType 이 그대로 나가야 한다. 쓰기만 ADMIN 이므로 이 GET 은 공개다(공개 게시물의 일부).
     */
    @Operation(summary = "게시글 이미지 조회 API", description = "글 본문에 첨부된 이미지를 스트리밍합니다.")
    @GetMapping("/{postId}/images/{fileName}")
    public ResponseEntity<byte[]> getPostImage(
            @PathVariable("postId") Long postId,
            @PathVariable("fileName") String fileName
    ) {
        PostService.PostImage image = postService.loadPostImage(postId, fileName);

        return ResponseEntity.ok()
                .contentType(image.contentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(image.bytes());
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

    @PutMapping(value = "/{postId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "게시글 수정 API", description = "게시글 제목·본문·카테고리를 수정하고 첨부 파일을 추가합니다.")
    public ApiResponse<Void> updatePost(
            @PathVariable(value = "postId") Long postId,
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestPart("request") PostPostRequest request,
            @RequestPart(value = "markdownEn", required = false) MultipartFile markdownEn,
            @RequestPart(value = "markdownKr", required = false) MultipartFile markdownKr,
            @RequestPart(value = "file", required = false) List<MultipartFile> files
    ) {
        postService.updatePost(postId, request, loginId, markdownEn, markdownKr, files);
        return ApiResponse.success("Post updated");
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
