package com.woobeee.mvc.pokemon.api.response;

import java.util.List;

/**
 * {@code /pokemon/{handle}} — 한 주최자와 그가 연 차수들, 그리고 그 차수들의 신청서를 모은 것.
 *
 * @param bankAccount 기본 입금 계좌. <b>본인에게만</b> 내보낸다 — 차수를 열기 전이라면 아직
 *                    공개할 이유가 없다(차수를 열면 그 차수의 계좌로 모두에게 보인다)
 * @param orders      모든 차수의 신청서를 최신순으로 모은 것. 어느 차수인지는 각 신청서의
 *                    {@code roundId} 로 찾는다 — 차수 목록이 함께 오므로 화면에서 이어 붙인다
 */
public record PokemonHostResponse(
        String handle,
        String name,
        boolean isMe,
        String bankAccount,
        List<PokemonRoundResponse> rounds,
        List<PokemonOrderResponse> orders
) {}
