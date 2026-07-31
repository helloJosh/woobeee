package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.api.request.PostCommentRequest;
import com.woobeee.mvc.blog.api.response.GetCommentResponse;

import java.util.List;

public interface CommentService {
    void saveComment(PostCommentRequest request, String loginId);
    void deleteComment(Long commentId, String loginId);
    List<GetCommentResponse> getAllCommentsFromPost(Long postId, String loginId);
}
