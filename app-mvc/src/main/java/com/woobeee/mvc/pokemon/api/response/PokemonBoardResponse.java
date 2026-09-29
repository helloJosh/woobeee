package com.woobeee.mvc.pokemon.api.response;

import java.util.List;

/**
 * 진행도 페이지가 한 번에 받는 것 전부: 현재 환율, 상품표, 신청서 목록(댓글 포함), 입금 계좌.
 * 집계(총액·상태별 진행도·환차손익)는 여기서 계산하지 않는다 — front/lib/pokemon.ts 가 한다.
 */
public record PokemonBoardResponse(
        ExchangeRateResponse rate,
        List<PokemonProductResponse> products,
        List<PokemonOrderResponse> orders,
        String bankAccount,
        /** 이 요청을 보낸 사람이 진행 상태를 바꿀 수 있는 운영자인가. */
        boolean canManage
) {}
