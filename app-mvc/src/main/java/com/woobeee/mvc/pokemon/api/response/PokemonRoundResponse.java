package com.woobeee.mvc.pokemon.api.response;

import com.woobeee.mvc.pokemon.entity.PokemonRounds;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 차수 한 건.
 *
 * @param hostHandle URL 조각 — {@code /pokemon/{hostHandle}/{sequence}}
 * @param canManage  이 응답을 받는 사람이 이 차수를 주무를 수 있는가(주최자이거나 운영자)
 * @param orderCount 취소를 뺀 신청 건수
 */
public record PokemonRoundResponse(
        Long id,
        String hostHandle,
        String hostName,
        int sequence,
        String title,
        String currency,
        String rateMode,
        BigDecimal quotedRate,
        LocalDateTime quotedAt,
        String bankAccount,
        LocalDate deadline,
        String status,
        BigDecimal settledRate,
        LocalDateTime settledAt,
        String memo,
        LocalDateTime createdAt,
        boolean canManage,
        int orderCount,
        long transferKrwTotal
) {

    public static PokemonRoundResponse of(PokemonRounds round, String hostHandle, String hostName,
                                          boolean canManage, int orderCount, long transferKrwTotal) {
        return new PokemonRoundResponse(
                round.getId(), hostHandle, hostName, round.getSequence(), round.getTitle(),
                round.getCurrency(), round.getRateMode().name(), round.getQuotedRate(),
                round.getQuotedAt(), round.getBankAccount(), round.getDeadline(),
                round.getStatus().name(), round.getSettledRate(), round.getSettledAt(),
                round.getMemo(), round.getCreatedAt(), canManage, orderCount, transferKrwTotal);
    }
}
