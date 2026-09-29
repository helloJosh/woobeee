package com.woobeee.mvc.pokemon.api.response;

import com.woobeee.mvc.pokemon.entity.PokemonOrders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 신청서 한 건. 환율은 <b>신청 당시 값</b>({@code quotedRate})이 그대로 실려 나간다 —
 * 현재 환율과의 차이(환차손익)는 받는 쪽이 계산한다. 단 결제까지 끝난 건은
 * {@code settledRate} 가 채워져 있고, 그때는 그 값으로 손익이 확정된다. front/lib/pokemon.ts 참고.
 */
public record PokemonOrderResponse(
        Long id,
        String applicantName,
        String depositorName,
        boolean guest,
        /** 이 응답을 받는 사람이 낸 신청서면 true. 회원 id 를 내보내지 않으려고 서버가 판단한다. */
        boolean mine,
        String status,
        BigDecimal totalInr,
        /** 상품표에 없는 것을 위한 자유 입력 루피. 없으면 0. */
        BigDecimal extraInr,
        /** 이 신청서로 받는 코인 총합 — 항목의 코인 x 수량 합. */
        long totalCoins,
        BigDecimal quotedRate,
        LocalDateTime quotedAt,
        long itemsKrw,
        long donationKrw,
        long transferKrw,
        BigDecimal settledRate,
        LocalDateTime settledAt,
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
                order.getApplicantName(),
                order.getDepositorName(),
                order.getMemberId() == null,
                order.isOwnedBy(viewerMemberId),
                order.getStatus().name(),
                order.getTotalInr(),
                order.getExtraInr(),
                items.stream().mapToLong(item -> (long) item.coins() * item.quantity()).sum(),
                order.getQuotedRate(),
                order.getQuotedAt(),
                order.getItemsKrw(),
                order.getDonationKrw(),
                order.getTransferKrw(),
                order.getSettledRate(),
                order.getSettledAt(),
                order.getMemo(),
                order.getCreatedAt(),
                items,
                comments);
    }
}
