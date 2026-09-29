package com.woobeee.mvc.pokemon.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.pokemon.api.request.PatchPokemonOrderStatusRequest;
import com.woobeee.mvc.pokemon.api.request.PostPokemonCommentRequest;
import com.woobeee.mvc.pokemon.api.request.PostPokemonOrderRequest;
import com.woobeee.mvc.pokemon.api.request.PutPokemonOrderRequest;
import com.woobeee.mvc.pokemon.api.response.ExchangeRateResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonBoardResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonCommentResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonOrderDetailResponse;
import com.woobeee.mvc.pokemon.api.response.PokemonOrderResponse;
import com.woobeee.mvc.pokemon.service.PokemonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 포켓코인 공동구매 신청·진행도.
 *
 * <p>조회·신청·댓글은 <b>공개</b>다 — 친구들이 로그인 없이 쓸 수 있어야 하고 진행도는 모두가
 * 본다. 진행 상태 변경은 운영자(PokemonProperties.managerMemberIds) 전용이고, 삭제는 운영자
 * 또는 아직 입금 전인 본인 신청만 가능하다.
 * loginId 헤더는 AccessTokenLoginIdHeaderFilter 가 주입하므로 없으면 비회원이다.
 */
@RestController
@RequestMapping("/api/back/pokemon")
@Tag(name = "Pokemon Controller", description = "포켓코인 공동구매 컨트롤러")
@RequiredArgsConstructor
public class PokemonController {
    private final PokemonService pokemonService;

    @GetMapping("/board")
    @Operation(summary = "진행도 조회",
            description = "현재 환율, 상품표, 신청서 전체(댓글 포함), 입금 계좌, 운영자 여부를 한 번에 가져옵니다.")
    public ApiResponse<PokemonBoardResponse> getBoard(
            @RequestHeader(name = "loginId", required = false) String loginId) {
        return ApiResponse.success(pokemonService.getBoard(loginId), "Pokemon board retrieved");
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "신청서 세부 조회", description = "신청서 한 건 + 현재 환율 + 계좌 + 운영자 여부.")
    public ApiResponse<PokemonOrderDetailResponse> getOrder(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long orderId) {
        return ApiResponse.success(pokemonService.getOrder(loginId, orderId), "Order retrieved");
    }

    @GetMapping("/rate")
    @Operation(summary = "현재 환율 조회", description = "1 INR 이 몇 원인지. 캐시된 값이며 stale 이면 지난 값입니다.")
    public ApiResponse<ExchangeRateResponse> getRate() {
        return ApiResponse.success(pokemonService.getRate(), "Exchange rate retrieved");
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "신청서 제출", description = "로그인 시 닉네임이 신청자명이 되고, 비회원은 이름을 직접 적습니다.")
    public ApiResponse<PokemonOrderResponse> createOrder(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestBody PostPokemonOrderRequest request) {
        return ApiResponse.createSuccess(pokemonService.createOrder(loginId, request), "Order created");
    }

    @PutMapping("/orders/{orderId}")
    @Operation(summary = "신청서 수정",
            description = "항목·기부금·메모를 전체 교체하고 금액을 수정 시점 환율로 다시 계산합니다. "
                    + "운영자이거나, 아직 주문 단계인 본인(비회원 신청은 누구나) 신청서만 고칠 수 있습니다.")
    public ApiResponse<PokemonOrderResponse> updateOrder(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long orderId,
            @Valid @RequestBody PutPokemonOrderRequest request) {
        return ApiResponse.success(pokemonService.updateOrder(loginId, orderId, request), "Order updated");
    }

    @PatchMapping("/orders/{orderId}/status")
    @Operation(summary = "진행 상태 변경", description = "운영자 전용.")
    public ApiResponse<PokemonOrderResponse> changeStatus(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long orderId,
            @Valid @RequestBody PatchPokemonOrderStatusRequest request) {
        return ApiResponse.success(pokemonService.changeStatus(loginId, orderId, request), "Order status changed");
    }

    @DeleteMapping("/orders/{orderId}")
    @Operation(summary = "신청서 삭제", description = "운영자이거나, 아직 입금 전인 본인 신청일 때만 가능합니다.")
    public ApiResponse<Void> deleteOrder(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long orderId) {
        pokemonService.deleteOrder(loginId, orderId);
        return ApiResponse.success("Order deleted");
    }

    @PostMapping("/orders/{orderId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "신청서에 댓글 달기", description = "로그인 시 닉네임이 작성자가 되고, 비회원은 이름을 직접 적습니다.")
    public ApiResponse<PokemonCommentResponse> createComment(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long orderId,
            @Valid @RequestBody PostPokemonCommentRequest request) {
        return ApiResponse.createSuccess(
                pokemonService.createComment(loginId, orderId, request), "Comment created");
    }

    @DeleteMapping("/comments/{commentId}")
    @Operation(summary = "댓글 삭제", description = "운영자이거나 본인이 쓴 댓글일 때만 가능합니다.")
    public ApiResponse<Void> deleteComment(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long commentId) {
        pokemonService.deleteComment(loginId, commentId);
        return ApiResponse.success("Comment deleted");
    }
}
