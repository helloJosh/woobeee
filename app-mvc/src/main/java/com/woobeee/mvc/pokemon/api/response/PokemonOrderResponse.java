package com.woobeee.mvc.pokemon.api.response;

import com.woobeee.mvc.pokemon.entity.PokemonOrders;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 신청서 한 건. 원화는 저장하지 않는다 — 루피 합계에 <b>오늘 환율</b>을 곱한 <b>예상 금액</b>이
 * 응답마다 새로 계산돼 실린다(POKEMON-AC-04). front/lib/pokemon.ts 참고.
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
        /** round(루피 합계 × 오늘 환율). 기부금을 뺀 예상 상품값. 환율을 모르면 null. */
        Long estimatedItemsKrw,
        long donationKrw,
        /** estimatedItemsKrw + donationKrw — 예상 이체 금액. 실제 청구와 다를 수 있다. */
        Long estimatedKrw,
        String memo,
        LocalDateTime createdAt,
        List<PokemonOrderItemResponse> items,
        List<PokemonCommentResponse> comments
) {

    public static PokemonOrderResponse of(PokemonOrders order,
                                          List<PokemonOrderItemResponse> items,
                                          List<PokemonCommentResponse> comments,
                                          Long viewerMemberId,
                                          BigDecimal rateToKrw) {
        Long estimatedItemsKrw = rateToKrw == null ? null : order.getTotalAmount().multiply(rateToKrw)
                .setScale(0, RoundingMode.HALF_UP).longValue();
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
                estimatedItemsKrw,
                order.getDonationKrw(),
                estimatedItemsKrw == null ? null : estimatedItemsKrw + order.getDonationKrw(),
                order.getMemo(),
                order.getCreatedAt(),
                items,
                comments);
    }
}
