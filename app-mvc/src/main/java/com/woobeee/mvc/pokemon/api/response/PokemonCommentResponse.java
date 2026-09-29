package com.woobeee.mvc.pokemon.api.response;

import com.woobeee.mvc.pokemon.entity.PokemonOrderComments;

import java.time.LocalDateTime;

/**
 * @param guest 비회원이 쓴 댓글이면 true
 * @param mine  이 응답을 받는 사람이 쓴 댓글이면 true. 작성자의 회원 id 를 그대로 내보내지
 *              않으려고 서버가 대신 판단한다 — 화면은 이 값으로만 삭제 버튼을 결정한다.
 */
public record PokemonCommentResponse(
        Long id,
        Long orderId,
        String authorName,
        boolean guest,
        boolean mine,
        String content,
        LocalDateTime createdAt
) {

    public static PokemonCommentResponse of(PokemonOrderComments comment, Long viewerMemberId) {
        return new PokemonCommentResponse(
                comment.getId(),
                comment.getOrderId(),
                comment.getAuthorName(),
                comment.getMemberId() == null,
                comment.isWrittenBy(viewerMemberId),
                comment.getContent(),
                comment.getCreatedAt());
    }
}
