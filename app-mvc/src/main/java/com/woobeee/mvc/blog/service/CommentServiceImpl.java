package com.woobeee.mvc.blog.service;

import com.woobeee.mvc.blog.api.request.PostCommentRequest;
import com.woobeee.mvc.blog.api.response.GetCommentResponse;
import com.woobeee.mvc.blog.entity.Comments;
import com.woobeee.mvc.blog.exception.CustomAuthenticationException;
import com.woobeee.mvc.blog.exception.CustomNotFoundException;
import com.woobeee.mvc.blog.exception.ErrorCode;
import com.woobeee.mvc.blog.repository.CommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class CommentServiceImpl implements CommentService {
    private final CommentRepository commentRepository;
    private final AuthMemberResolver authMemberResolver;

    @Override
    public void saveComment (
            PostCommentRequest request,
            String loginId
    ) {
        if (loginId == null) {
            throw new CustomAuthenticationException(ErrorCode.comment_needAuthentication);
        }

        AuthMemberResolver.MemberIdentity memberIdentity = authMemberResolver.requireByLoginId(loginId);

        Comments comment = new Comments (
                request.content(),
                request.postId(),
                request.parentId(),
                memberIdentity.memberId(),
                memberIdentity.role()
        );

        commentRepository.save(comment);
    }

    @Override
    public void deleteComment (
            Long commentId,
            String loginId
    ) {
        if (loginId == null) {
            throw new CustomAuthenticationException(ErrorCode.comment_needAuthentication);
        }

        AuthMemberResolver.MemberIdentity memberIdentity = authMemberResolver.requireByLoginId(loginId);

        Comments comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new RuntimeException("댓글이 존재하지 않습니다."));

        if (!comment.getMemberId().equals(memberIdentity.memberId())
                || !comment.getMemberRole().equals(memberIdentity.role())) {
            throw new RuntimeException("댓글을 삭제할 권한이 없습니다.");
        }

        commentRepository.delete(comment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GetCommentResponse> getAllCommentsFromPost (
            Long postId,
            String loginId
    ) {
        List<Comments> comments = commentRepository.findAllByPostId(postId);

        Map<Long, GetCommentResponse> map = new HashMap<>();

        List<GetCommentResponse> roots = new ArrayList<>();

        for (Comments comment : comments) {
            String authorLoginId = authMemberResolver.resolveLoginId(comment.getMemberId(), comment.getMemberRole());

            map.put(comment.getId(), new GetCommentResponse(
                    comment.getId(),
                    authorLoginId,
                    authorLoginId.equals(loginId),
                    comment.getContent(),
                    comment.getCreatedAt(),
                    new ArrayList<>()
            ));
        }

        // 4. 댓글 계층 구성
        for (Comments comment : comments) {
            if (comment.getParentId() == null) {
                // 최상위 댓글
                roots.add(map.get(comment.getId()));
            } else {
                // 자식 댓글 → 부모에 추가
                GetCommentResponse parent = map.get(comment.getParentId());
                if (parent != null) {
                    parent.replies().add(map.get(comment.getId()));
                }
            }
        }

        return roots;
    }
}
