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

    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal quotedRate;

    @Column(nullable = false)
    private LocalDateTime quotedAt;

    @Column(nullable = false)
    private long itemsKrw;

    @Column(nullable = false)
    private long donationKrw;

    @Column(nullable = false)
    private long transferKrw;


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
                          BigDecimal quotedRate,
                          LocalDateTime quotedAt, long itemsKrw, long donationKrw, long transferKrw,
                          String memo) {
        this.roundId = roundId;
        this.memberId = memberId;
        this.applicantName = applicantName;
        this.depositorName = depositorName;
        this.status = status;
        this.totalAmount = totalAmount;
        this.extraAmount = extraAmount;
        this.quotedRate = quotedRate;
        this.quotedAt = quotedAt;
        this.itemsKrw = itemsKrw;
        this.donationKrw = donationKrw;
        this.transferKrw = transferKrw;
        this.memo = memo;
    }

    public static PokemonOrders create(Long roundId, Long memberId, String applicantName, String depositorName,
                                       BigDecimal totalAmount, BigDecimal extraAmount,
                                       BigDecimal quotedRate, LocalDateTime quotedAt,
                                       long itemsKrw, long donationKrw, String memo) {
        return PokemonOrders.builder()
                .roundId(roundId)
                .memberId(memberId)
                .applicantName(applicantName)
                .depositorName(depositorName)
                .status(PokemonOrderStatus.ORDERED)
                .totalAmount(totalAmount)
                .extraAmount(extraAmount)
                .quotedRate(quotedRate)
                .quotedAt(quotedAt)
                .itemsKrw(itemsKrw)
                .donationKrw(donationKrw)
                .transferKrw(itemsKrw + donationKrw)
                .memo(memo)
                .build();
    }

    /**
     * 수정 — 금액을 통째로 다시 세운다. 주문 내용이 바뀌면 이체할 금액도 바뀌므로 환율
     * 스냅샷도 함께 갱신한다. 그래야 {@code quotedRate} 가 계속 "이 금액의 근거" 로 남는다.
     *
     * <p>환차손익은 차수가 확정하므로(차수의 {@code settledRate}) 여기서는 신경 쓰지 않는다.
     */
    public void reprice(String applicantName, String depositorName, BigDecimal totalAmount,
                        BigDecimal extraAmount, BigDecimal quotedRate, LocalDateTime quotedAt,
                        long itemsKrw, long donationKrw, String memo) {
        this.applicantName = applicantName;
        this.depositorName = depositorName;
        this.totalAmount = totalAmount;
        this.extraAmount = extraAmount;
        this.quotedRate = quotedRate;
        this.quotedAt = quotedAt;
        this.itemsKrw = itemsKrw;
        this.donationKrw = donationKrw;
        this.transferKrw = itemsKrw + donationKrw;
        this.memo = memo;
    }

    /** 환율을 박지 않는다 — 결제는 차수 단위라 환차 확정도 차수가 한다. */
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
