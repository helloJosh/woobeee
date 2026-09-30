package com.woobeee.mvc.pokemon.api.response;

import java.util.List;

/**
 * {@code /pokemon/{handle}} — 한 주최자와 그가 연 차수들.
 *
 * <p>신청서는 싣지 않는다. 화면이 차수 탭으로 나뉘어 한 번에 한 차수만 보므로, 그 차수의
 * 신청서는 차수 조회가 준다.
 *
 * @param bankAccount 기본 입금 계좌. <b>본인에게만</b> 내보낸다 — 차수를 열기 전이라면 아직
 *                    공개할 이유가 없다(차수를 열면 그 차수의 계좌로 모두에게 보인다)
 */
public record PokemonHostResponse(
        String handle,
        String name,
        boolean isMe,
        String bankAccount,
        List<PokemonRoundResponse> rounds
) {}
