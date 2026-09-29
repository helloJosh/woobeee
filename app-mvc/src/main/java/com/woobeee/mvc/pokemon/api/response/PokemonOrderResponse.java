package com.woobeee.mvc.pokemon.api.response;

import com.woobeee.mvc.pokemon.entity.PokemonOrders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 신청서 한 건. 환율은 <b>신청 당시 값</b>({@code quotedRate})이 그대로 실려 나간다 —
 * 환차손익은 <b>차수</b>가 확정한다(차수의 settledRate) — 결제가 차수 단위이기 때문이다.
 * front/lib/pokemon.ts 참고.
 */
public record PokemonOrderResponse(
        Long id,
        Long roundId,
        String applicantName,
        String depositorName,
        boolean guest,
        /** 이 응답을 받는 사람이 낸 신청서면 true. 회원 id 를 내보내지 않으려고 서버가 판단한다. */
        boolean mine,
        String status,
        BigDecimal totalAmount,
        /** 상품표에 없는 것을 위한 자유 입력 루피. 없으면 0. */
        BigDecimal extraAmount,
        /** 이 신청서로 받는 코인 총합 — 항목의 코인 x 수량 합. */
        long totalCoins,
        BigDecimal quotedRate,
        LocalDateTime quotedAt,
        long itemsKrw,
        long donationKrw,
        long transferKrw,
        String memo,
        LocalDateTime createdAt,
        List<PokemonOrderItemResponse> items,
        List<PokemonCommentResponse> comments
) {

    public static PokemonOrderResponse of(PokemonOrders order,
                                          List<PokemonOrderItemResponse> items,
                                          List<PokemonCommentResponse> comments,
                                          Long viewerMemberId) {
        return new PokemonOrderResponse(
                order.getId(),
                order.getRoundId(),
                order.getApplicantName(),
                order.getDepositorName(),
                order.getMemberId() == null,
                order.isOwnedBy(viewerMemberId),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getExtraAmount(),
                items.stream().mapToLong(item -> (long) item.coins() * item.quantity()).sum(),
                order.getQuotedRate(),
                order.getQuotedAt(),
                order.getItemsKrw(),
                order.getDonationKrw(),
                order.getTransferKrw(),
                order.getMemo(),
                order.getCreatedAt(),
                items,
                comments);
    }
}
