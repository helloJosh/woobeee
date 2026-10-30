package com.woobeee.mvc.pokemon.api.request;

import jakarta.validation.constraints.Size;

/**
 * 댓글 수정. 내용만 바꾼다 — 작성자와 신청서는 옮기지 않는다.
 *
 * @param content 빈 값 검사는 {@link PostPokemonCommentRequest} 와 같은 이유로 <b>서비스가</b>
 *                한다. 여기에 {@code @NotBlank} 를 달면 구체적인 {@code pokemon_emptyComment}
 *                대신 뭉뚱그린 {@code pokemon_badRequest} 가 나간다.
 */
public record PutPokemonCommentRequest(@Size(max = 500) String content) {}
