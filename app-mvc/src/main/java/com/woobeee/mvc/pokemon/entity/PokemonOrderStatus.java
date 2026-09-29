package com.woobeee.mvc.pokemon.entity;

/**
 * 신청서의 진행 단계. 코인 지급은 수동이라 상태만 관리한다.
 *
 * <p>ORDERED -> PREPARING -> DEPOSIT_CONFIRMED -> DELIVERED 가 정상 경로이고,
 * CANCELLED 는 어느 단계에서든 갈 수 있다.
 *
 * <p>준비중이 입금확인보다 <b>앞</b>이다 — 돈을 받기 전에 먼저 사 두는 운영 방식이기 때문이다.
 * 그래서 환차손익도 준비중으로 넘어갈 때 확정된다(그때가 실제로 돈을 쓰는 시점이다).
 */
public enum PokemonOrderStatus {
    /** 신청서만 들어온 상태. */
    ORDERED,
    /** 인도 스토어에서 결제하고 물건을 준비하는 중 — 환차손익이 여기서 확정된다. */
    PREPARING,
    /** 계좌 입금을 확인했다. */
    DEPOSIT_CONFIRMED,
    /** 코인이 신청자 계정에 들어갔다. */
    DELIVERED,
    /** 취소. 집계에서 제외한다. */
    CANCELLED;

    /** 취소되지 않은 신청서만 금액 집계에 들어간다. */
    public boolean countsTowardTotals() {
        return this != CANCELLED;
    }
}
