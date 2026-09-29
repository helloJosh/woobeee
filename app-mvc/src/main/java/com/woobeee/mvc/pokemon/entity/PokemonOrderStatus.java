package com.woobeee.mvc.pokemon.entity;

/**
 * 신청서 한 건의 진행 단계. <b>개인별로 다른 것</b>만 여기 있다 — 이 사람이 돈을 냈는가,
 * 받았는가. 주최자가 전체에 대해 하는 일(마감·결제·수령)은 {@link PokemonRoundStatus} 다.
 *
 * <p>ORDERED -> DEPOSIT_CONFIRMED -> DELIVERED 가 정상 경로이고, CANCELLED 는 어디서든 갈 수 있다.
 */
public enum PokemonOrderStatus {
    /** 신청서만 들어온 상태. 입금 대기. */
    ORDERED,
    /** 이 사람의 입금을 확인했다. */
    DEPOSIT_CONFIRMED,
    /** 이 사람에게 전달을 마쳤다. */
    DELIVERED,
    /** 취소. 집계에서 제외한다. */
    CANCELLED;

    /** 취소되지 않은 신청서만 금액 집계에 들어간다. */
    public boolean countsTowardTotals() {
        return this != CANCELLED;
    }
}
