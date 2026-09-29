package com.woobeee.mvc.pokemon.entity;

/**
 * 차수의 진행 단계. 주최자가 <b>전체에 대해</b> 하는 일이 여기 있다 — 신청을 닫고, 한 번에
 * 결제하고, 받아서 나눠 준다. 개인별로 다른 것(입금했는가)은 신청서가 들고 있다.
 *
 * <p>OPEN -> CLOSED -> PURCHASED -> DELIVERED 가 정상 경로이고, CANCELLED 는 어디서든 갈 수 있다.
 */
public enum PokemonRoundStatus {
    /** 모집중. 이때만 신청을 받는다. */
    OPEN,
    /** 마감. 더 받지 않고 입금을 모은다. */
    CLOSED,
    /** 주최자가 스토어에서 결제했다 — 환차손익이 여기서 확정된다. */
    PURCHASED,
    /** 참가자에게 전부 전달했다. */
    DELIVERED,
    /** 취소. 집계에서 빠진다. */
    CANCELLED;

    /** 신청을 받을 수 있는가. 모집중일 때만이다. */
    public boolean acceptsOrders() {
        return this == OPEN;
    }

    public boolean countsTowardTotals() {
        return this != CANCELLED;
    }
}
