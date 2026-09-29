package com.woobeee.mvc.pokemon.service;

import com.woobeee.mvc.pokemon.api.request.PatchPokemonOrderStatusRequest;
import com.woobeee.mvc.pokemon.api.request.PokemonProductRequest;
import com.woobeee.mvc.pokemon.api.request.PostPokemonCommentRequest;
import com.woobeee.mvc.pokemon.api.request.PostPokemonOrderRequest;
import com.woobeee.mvc.pokemon.api.request.PutPokemonOrderRequest;
import com.woobeee.mvc.pokemon.api.response.ExchangeRateResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonBoardResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonCommentResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonManagedProductResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonOrderDetailResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonOrderResponse;

import java.util.List;

public interface PokemonService {

    /** 진행도 페이지가 쓰는 단일 조회 — 환율·상품표·신청서 전체(댓글 포함)·계좌·운영자 여부. */
    PokemonBoardResponse getBoard(String loginId);

    /** 세부 페이지용 단건 조회 — 목록과 같은 부속(환율·계좌·운영자 여부)을 함께 준다. */
    PokemonOrderDetailResponse getOrder(String loginId, Long orderId);

    ExchangeRateResponse getRate();

    PokemonOrderResponse createOrder(String loginId, PostPokemonOrderRequest request);

    /** 전체 교체. 금액은 수정 시점 환율로 다시 계산된다. */
    PokemonOrderResponse updateOrder(String loginId, Long orderId, PutPokemonOrderRequest request);

    PokemonOrderResponse changeStatus(String loginId, Long orderId, PatchPokemonOrderStatusRequest request);

    void deleteOrder(String loginId, Long orderId);

    PokemonCommentResponse createComment(String loginId, Long orderId, PostPokemonCommentRequest request);

    void deleteComment(String loginId, Long commentId);

    /* ===== 상품 관리 (운영자 전용) ===== */

    /** 내려간 것까지 전부. 신청 화면의 상품표와 달리 관리용이다. */
    List<PokemonManagedProductResponse> getManagedProducts(String loginId);

    PokemonManagedProductResponse createProduct(String loginId, PokemonProductRequest request);

    PokemonManagedProductResponse updateProduct(String loginId, Long productId, PokemonProductRequest request);

    void deleteProduct(String loginId, Long productId);
}
