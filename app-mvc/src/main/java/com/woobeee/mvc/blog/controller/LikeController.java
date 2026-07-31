package com.woobeee.mvc.blog.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.blog.service.LikeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/back/likes")
@Tag(name = "Like Controller", description = "좋아요 컨트롤러")
@RequiredArgsConstructor
public class LikeController {
    private final LikeService likeService;

    @PostMapping("/{postId}")
    @Operation(summary = "좋아요 등록 API", description = "게시글에 좋아요를 등록합니다.")
    public ApiResponse<Void> saveLike(
            @PathVariable(value = "postId") Long postId,
            @RequestHeader(name = "loginId", required = false) String loginId
    ) {
        likeService.saveLike(postId, loginId);
        return ApiResponse.success("Like saved");
    }

    @DeleteMapping("/{postId}")
    @Operation(summary = "좋아요 취소 API", description = "게시글 좋아요를 취소합니다.")
    public ApiResponse<Void> deleteLike(
            @PathVariable(value = "postId") Long postId,
            @RequestHeader(name = "loginId", required = false) String loginId
    ) {
        likeService.deleteLike(postId, loginId);
        return ApiResponse.success("Like deleted");
    }
}
