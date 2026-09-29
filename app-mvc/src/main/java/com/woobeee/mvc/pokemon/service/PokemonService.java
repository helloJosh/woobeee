package com.woobeee.mvc.pokemon.service;

import com.woobeee.mvc.pokemon.api.request.*;
import com.woobeee.mvc.pokemon.api.response.*;

import java.util.List;

public interface PokemonService {

    /* ===== 첫 화면 · 주최자 ===== */

    /** {@code /pokemon} — 최근에 열린 차수들과 내 주소. */
    PokemonHomeResponse getHome(String loginId);

    /** {@code /pokemon/{handle}} — 한 주최자와 그가 연 차수들. */
    PokemonHostResponse getHost(String loginId, String handle);

    /** 주소 정하기. 회원당 한 번이고, 정해야 차수를 열 수 있다. */
    PokemonHostResponse claimHandle(String loginId, PokemonHandleRequest request);

    /** 주최자 설정 — 기본 입금 계좌. 주소는 바꾸지 않는다. */
    PokemonHostResponse updateHostSettings(String loginId, PokemonHostSettingsRequest request);

    /* ===== 차수 ===== */

    PokemonRoundResponse openRound(String loginId, PostPokemonRoundRequest request);

    /** {@code /pokemon/{handle}/{sequence}} — 차수·상품표·신청서 전체. */
    PokemonRoundBoardResponse getRoundBoard(String loginId, String handle, int sequence);

    PokemonRoundResponse updateRound(String loginId, Long roundId, PutPokemonRoundRequest request);

    PokemonRoundResponse changeRoundStatus(String loginId, Long roundId,
                                           PatchPokemonRoundStatusRequest request);

    void deleteRound(String loginId, Long roundId);

    /* ===== 신청서 ===== */

    PokemonOrderDetailResponse getOrder(String loginId, Long orderId);

    PokemonOrderResponse createOrder(String loginId, Long roundId, PostPokemonOrderRequest request);

    PokemonOrderResponse updateOrder(String loginId, Long orderId, PutPokemonOrderRequest request);

    PokemonOrderResponse changeStatus(String loginId, Long orderId, PatchPokemonOrderStatusRequest request);

    void deleteOrder(String loginId, Long orderId);

    /* ===== 댓글 ===== */

    PokemonCommentResponse createComment(String loginId, Long orderId, PostPokemonCommentRequest request);

    void deleteComment(String loginId, Long commentId);

    /* ===== 상품 관리 (주최자 각자의 상품표) ===== */

    List<PokemonManagedProductResponse> getManagedProducts(String loginId);

    PokemonManagedProductResponse createProduct(String loginId, PokemonProductRequest request);

    PokemonManagedProductResponse updateProduct(String loginId, Long productId, PokemonProductRequest request);

    void deleteProduct(String loginId, Long productId);
}
