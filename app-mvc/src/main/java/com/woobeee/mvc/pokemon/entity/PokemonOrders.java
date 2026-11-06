package com.woobeee.mvc.pokemon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Entity
@Table(name = "pokemon_orders")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PokemonOrders {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 어느 차수의 신청인가. 환율·계좌·통화는 차수가 들고 있다. */
    @Column(nullable = false)
    private Long roundId;

    /** 로그인 신청이면 회원 id, 비회원 신청이면 null. */
    private Long memberId;

    @Column(nullable = false, length = 60)
    private String applicantName;

    @Column(length = 60)
    private String depositorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PokemonOrderStatus status;

    /** 상품 합계 + {@link #extraAmount}. 차수 통화 기준의 총액이다. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount;

    /** 상품표에 없는 것을 위한 자유 입력 금액(차수 통화). 없으면 0. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal extraAmount;

    /** 신청자가 직접 적은 기부금(원). 환율과 무관하다. 원화 상품값은 저장하지 않고 매번 계산한다. */
    @Column(nullable = false)
    private long donationKrw;

    @Column(length = 500)
    private String memo;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    private PokemonOrders(Long roundId, Long memberId, String applicantName, String depositorName,
                          PokemonOrderStatus status, BigDecimal totalAmount, BigDecimal extraAmount,
                          long donationKrw, String memo) {
        this.roundId = roundId;
        this.memberId = memberId;
        this.applicantName = applicantName;
        this.depositorName = depositorName;
        this.status = status;
        this.totalAmount = totalAmount;
        this.extraAmount = extraAmount;
        this.donationKrw = donationKrw;
        this.memo = memo;
    }

    public static PokemonOrders create(Long roundId, Long memberId, String applicantName, String depositorName,
                                       BigDecimal totalAmount, BigDecimal extraAmount,
                                       long donationKrw, String memo) {
        return PokemonOrders.builder()
                .roundId(roundId)
                .memberId(memberId)
                .applicantName(applicantName)
                .depositorName(depositorName)
                .status(PokemonOrderStatus.ORDERED)
                .totalAmount(totalAmount)
                .extraAmount(extraAmount)
                .donationKrw(donationKrw)
                .memo(memo)
                .build();
    }

    /** 수정 — 금액을 통째로 다시 세운다. 원화는 저장하지 않으므로 루피 합계와 기부금만 바뀐다. */
    public void reprice(String applicantName, String depositorName, BigDecimal totalAmount,
                        BigDecimal extraAmount, long donationKrw, String memo) {
        this.applicantName = applicantName;
        this.depositorName = depositorName;
        this.totalAmount = totalAmount;
        this.extraAmount = extraAmount;
        this.donationKrw = donationKrw;
        this.memo = memo;
    }

    public void changeStatus(PokemonOrderStatus next) {
        this.status = next;
    }

    /**
     * 비회원 신청서는 주인이 없다. 그래서 "본인만" 이라는 제한을 걸 수 없고, 누구나 지울 수
     * 있게 둔다 — 잘못 낸 신청서를 본인이 거두지 못하는 쪽이 더 나쁘기 때문이다.
     */
    public boolean hasOwner() {
        return memberId != null;
    }

    public boolean isOwnedBy(Long candidateMemberId) {
        return memberId != null && memberId.equals(candidateMemberId);
    }
}
