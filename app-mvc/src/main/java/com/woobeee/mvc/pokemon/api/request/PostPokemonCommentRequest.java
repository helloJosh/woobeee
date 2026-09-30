package com.woobeee.mvc.pokemon.api.request;

import jakarta.validation.constraints.Size;

/**
 * 댓글은 로그인해야 쓴다 — 작성자는 언제나 회원 닉네임이다.
 *
 * @param content 빈 값 검사는 <b>서비스가</b> 한다. 여기에 {@code @NotBlank} 를 달면 bean
 *                validation 이 먼저 걸려 구체적인 {@code pokemon_emptyComment} 대신
 *                뭉뚱그린 {@code pokemon_badRequest} 가 나가고, 그 코드는 도달 불가가 된다.
 */
public record PostPokemonCommentRequest(@Size(max = 500) String content) {}
