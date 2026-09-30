package com.woobeee.mvc.pokemon.controller;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.mvc.pokemon.api.request.*;
import com.woobeee.mvc.pokemon.api.response.*;
import com.woobeee.mvc.pokemon.service.PokemonService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 포켓코인 공동구매. 주최자가 차수(1차·2차·3차)를 열고 친구들이 거기에 신청한다.
 *
 * <p>조회·신청·댓글은 <b>공개</b>다 — 친구들이 로그인 없이 쓸 수 있어야 한다. 차수를 여는 것만
 * 로그인이 필요하고(주최자를 특정해야 한다), 차수를 주무르는 것은 그 차수의 주최자
 * (또는 전역 운영자)뿐이다. loginId 헤더는 AccessTokenLoginIdHeaderFilter 가 주입한다.
 */
@RestController
@RequestMapping("/api/back/pokemon")
@Tag(name = "Pokemon Controller", description = "포켓코인 공동구매 컨트롤러")
@RequiredArgsConstructor
public class PokemonController {
    private final PokemonService pokemonService;

    /* ===== 첫 화면 · 주최자 ===== */

    @GetMapping("/home")
    @Operation(summary = "첫 화면", description = "최근에 열린 차수들, 내 주소, 고를 수 있는 통화.")
    public ApiResponse<PokemonHomeResponse> getHome(
            @RequestHeader(name = "loginId", required = false) String loginId) {
        return ApiResponse.success(pokemonService.getHome(loginId), "Pokemon home retrieved");
    }

    @GetMapping("/hosts/{handle}")
    @Operation(summary = "주최자와 그 차수들", description = "/pokemon/{handle} 화면.")
    public ApiResponse<PokemonHostResponse> getHost(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable String handle) {
        return ApiResponse.success(pokemonService.getHost(loginId, handle), "Host retrieved");
    }

    @PostMapping("/hosts")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "내 주소 정하기",
            description = "/pokemon/{handle} 로 쓰인다. 회원당 한 번이고, 정해야 차수를 열 수 있다.")
    public ApiResponse<PokemonHostResponse> claimHandle(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestBody PokemonHandleRequest request) {
        return ApiResponse.createSuccess(pokemonService.claimHandle(loginId, request), "Handle claimed");
    }

    @PutMapping("/hosts/me")
    @Operation(summary = "주최자 설정", description = "기본 입금 계좌. 차수를 열 때 자동으로 채워진다.")
    public ApiResponse<PokemonHostResponse> updateHostSettings(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestBody PokemonHostSettingsRequest request) {
        return ApiResponse.success(
                pokemonService.updateHostSettings(loginId, request), "Host settings updated");
    }

    /* ===== 차수 ===== */

    @PostMapping("/rounds")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "차수 개설",
            description = "통화와 환율 방식(FIXED/PER_ORDER)을 정한다. 차수 번호는 주최자 안에서 자동으로 매겨진다.")
    public ApiResponse<PokemonRoundResponse> openRound(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestBody PostPokemonRoundRequest request) {
        return ApiResponse.createSuccess(pokemonService.openRound(loginId, request), "Round opened");
    }

    @GetMapping("/hosts/{handle}/rounds/{sequence}")
    @Operation(summary = "차수 화면", description = "차수·상품표·신청서 전체·현재 환율을 한 번에 가져옵니다.")
    public ApiResponse<PokemonRoundBoardResponse> getRoundBoard(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable String handle,
            @PathVariable int sequence) {
        return ApiResponse.success(
                pokemonService.getRoundBoard(loginId, handle, sequence), "Round retrieved");
    }

    @PutMapping("/rounds/{roundId}")
    @Operation(summary = "차수 수정",
            description = "통화는 바꿀 수 없다. 환율을 바꾸면 앞으로 들어올 신청서에만 적용된다.")
    public ApiResponse<PokemonRoundResponse> updateRound(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long roundId,
            @Valid @RequestBody PutPokemonRoundRequest request) {
        return ApiResponse.success(pokemonService.updateRound(loginId, roundId, request), "Round updated");
    }

    @PatchMapping("/rounds/{roundId}/status")
    @Operation(summary = "차수 진행 상태 변경",
            description = "PURCHASED 로 처음 넘어갈 때 그 순간의 환율이 박혀 환차손익이 확정된다.")
    public ApiResponse<PokemonRoundResponse> changeRoundStatus(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long roundId,
            @Valid @RequestBody PatchPokemonRoundStatusRequest request) {
        return ApiResponse.success(
                pokemonService.changeRoundStatus(loginId, roundId, request), "Round status changed");
    }

    @DeleteMapping("/rounds/{roundId}")
    @Operation(summary = "차수 삭제", description = "신청서가 하나도 없을 때만. 있으면 취소 상태로 둔다.")
    public ApiResponse<Void> deleteRound(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long roundId) {
        pokemonService.deleteRound(loginId, roundId);
        return ApiResponse.success("Round deleted");
    }

    @GetMapping("/rounds/{roundId}/products")
    @Operation(summary = "차수 상품표", description = "그 차수의 상품 — 내려간 것까지. 차수마다 따로 고친다.")
    public ApiResponse<List<PokemonManagedProductResponse>> getRoundProducts(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long roundId) {
        return ApiResponse.success(
                pokemonService.getRoundProducts(loginId, roundId), "Round products retrieved");
    }

    @PostMapping("/rounds/{roundId}/products")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "차수에 상품 추가", description = "통화는 차수를 따라간다.")
    public ApiResponse<PokemonManagedProductResponse> createRoundProduct(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long roundId,
            @Valid @RequestBody PokemonProductRequest request) {
        return ApiResponse.createSuccess(
                pokemonService.createRoundProduct(loginId, roundId, request), "Product created");
    }

    @PostMapping("/rounds/{roundId}/products/from-template")
    @Operation(summary = "내 상품표에서 가져오기",
            description = "그 차수 통화의 틀을 복사한다. 이미 있는 이름은 건너뛴다.")
    public ApiResponse<List<PokemonManagedProductResponse>> copyTemplate(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long roundId) {
        return ApiResponse.success(pokemonService.copyTemplateInto(loginId, roundId), "Template copied");
    }

    /* ===== 신청서 ===== */

    @PostMapping("/rounds/{roundId}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "신청서 제출", description = "모집중인 차수에만 낼 수 있다. 비회원도 가능.")
    public ApiResponse<PokemonOrderResponse> createOrder(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long roundId,
            @Valid @RequestBody PostPokemonOrderRequest request) {
        return ApiResponse.createSuccess(
                pokemonService.createOrder(loginId, roundId, request), "Order created");
    }

    @GetMapping("/orders/{orderId}")
    @Operation(summary = "신청서 세부 조회", description = "신청서 한 건 + 그 차수 + 현재 환율.")
    public ApiResponse<PokemonOrderDetailResponse> getOrder(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long orderId) {
        return ApiResponse.success(pokemonService.getOrder(loginId, orderId), "Order retrieved");
    }

    @PutMapping("/orders/{orderId}")
    @Operation(summary = "신청서 수정", description = "항목·기부금·메모 전체 교체. 금액은 차수 규칙대로 다시 계산된다.")
    public ApiResponse<PokemonOrderResponse> updateOrder(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long orderId,
            @Valid @RequestBody PutPokemonOrderRequest request) {
        return ApiResponse.success(pokemonService.updateOrder(loginId, orderId, request), "Order updated");
    }

    @PatchMapping("/orders/{orderId}/status")
    @Operation(summary = "신청서 진행 상태 변경", description = "주최자 전용.")
    public ApiResponse<PokemonOrderResponse> changeStatus(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long orderId,
            @Valid @RequestBody PatchPokemonOrderStatusRequest request) {
        return ApiResponse.success(
                pokemonService.changeStatus(loginId, orderId, request), "Order status changed");
    }

    @DeleteMapping("/orders/{orderId}")
    @Operation(summary = "신청서 삭제", description = "주최자이거나, 아직 주문 단계인 본인 신청.")
    public ApiResponse<Void> deleteOrder(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long orderId) {
        pokemonService.deleteOrder(loginId, orderId);
        return ApiResponse.success("Order deleted");
    }

    /* ===== 댓글 ===== */

    @PostMapping("/orders/{orderId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "신청서에 댓글 달기")
    public ApiResponse<PokemonCommentResponse> createComment(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long orderId,
            @Valid @RequestBody PostPokemonCommentRequest request) {
        return ApiResponse.createSuccess(
                pokemonService.createComment(loginId, orderId, request), "Comment created");
    }

    @DeleteMapping("/comments/{commentId}")
    @Operation(summary = "댓글 삭제", description = "주최자이거나 본인이 쓴 댓글.")
    public ApiResponse<Void> deleteComment(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long commentId) {
        pokemonService.deleteComment(loginId, commentId);
        return ApiResponse.success("Comment deleted");
    }

    /* ===== 상품 관리 ===== */

    @GetMapping("/products")
    @Operation(summary = "내 상품표", description = "주최자마다 자기 상품표를 갖는다. 내려간 것까지 전부.")
    public ApiResponse<List<PokemonManagedProductResponse>> getManagedProducts(
            @RequestHeader(name = "loginId", required = false) String loginId) {
        return ApiResponse.success(pokemonService.getManagedProducts(loginId), "Products retrieved");
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "상품 등록", description = "인게임 상점을 보고 직접 넣는다. 통화도 함께 고른다.")
    public ApiResponse<PokemonManagedProductResponse> createProduct(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @Valid @RequestBody PokemonProductRequest request) {
        return ApiResponse.createSuccess(pokemonService.createProduct(loginId, request), "Product created");
    }

    @PutMapping("/products/{productId}")
    @Operation(summary = "상품 수정", description = "가격을 고쳐도 과거 신청서는 움직이지 않는다.")
    public ApiResponse<PokemonManagedProductResponse> updateProduct(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long productId,
            @Valid @RequestBody PokemonProductRequest request) {
        return ApiResponse.success(
                pokemonService.updateProduct(loginId, productId, request), "Product updated");
    }

    @DeleteMapping("/products/{productId}")
    @Operation(summary = "상품 삭제", description = "신청서에 쓰인 적이 없을 때만. 쓰였으면 내리기를 쓴다.")
    public ApiResponse<Void> deleteProduct(
            @RequestHeader(name = "loginId", required = false) String loginId,
            @PathVariable Long productId) {
        pokemonService.deleteProduct(loginId, productId);
        return ApiResponse.success("Product deleted");
    }
}
