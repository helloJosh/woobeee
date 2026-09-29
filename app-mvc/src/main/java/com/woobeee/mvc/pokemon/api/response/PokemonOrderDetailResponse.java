package com.woobeee.mvc.pokemon.api.response;

/**
 * 신청서 세부 페이지가 한 번에 받는 것. 목록({@link PokemonBoardResponse})과 달리 신청서 한
 * 건만 담지만, 환율·계좌·운영자 여부는 똑같이 필요하다 — 환차손익을 현재 환율로 평가해야 하고,
 * 아직 입금 전이면 어디로 보내야 하는지 보여줘야 하며, 상태 조작 UI 를 그릴지 정해야 한다.
 */
public record PokemonOrderDetailResponse(
        ExchangeRateResponse rate,
        PokemonOrderResponse order,
        String bankAccount,
        boolean canManage
) {}
