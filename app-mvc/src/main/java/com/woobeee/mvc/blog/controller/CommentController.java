package com.woobeee.mvc.blog.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.blog.api.request.PostCommentRequest;
import com.woobeee.mvc.blog.api.response.GetCommentResponse;
import com.woobeee.mvc.blog.service.CommentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/back/comments")
@Tag(name = "Comment Controller", description = "댓글 컨트롤러")
@RequiredArgsConstructor
public class CommentController {
    private final CommentService commentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "댓글 저장 API", description = "게시글에 댓글 또는 대댓글을 저장합니다.")
    public ApiResponse<Void> saveComment(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestBody PostCommentRequest request
    ) {
        commentService.saveComment(request, loginId);
        return ApiResponse.createSuccess("Comment created");
    }

    @DeleteMapping("/{commentId}")
    @Operation(summary = "댓글 삭제 API", description = "댓글을 삭제합니다.")
    public ApiResponse<Void> deleteComment(
            @PathVariable(value = "commentId") Long commentId,
            @RequestHeader(name = "loginId", required = false) String loginId
    ) {
        commentService.deleteComment(commentId, loginId);
        return ApiResponse.success("Comment deleted");
    }

    @Operation(summary = "댓글 조회 API", description = "게시글의 댓글 목록을 조회합니다.")
    @GetMapping("/{postId}")
    public ApiResponse<List<GetCommentResponse>> getAllCommentsFromPost(
            @PathVariable(value = "postId") Long postId,
            @RequestHeader(name = "loginId", required = false) String loginId
    ) {
        List<GetCommentResponse> response = commentService.getAllCommentsFromPost(postId, loginId);
        return ApiResponse.success(response, "Comments retrieved");
    }
}
