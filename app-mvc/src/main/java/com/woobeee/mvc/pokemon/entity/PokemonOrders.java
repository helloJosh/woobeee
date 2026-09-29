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

    /** 로그인 신청이면 회원 id, 비회원 신청이면 null. */
    private Long memberId;

    @Column(nullable = false, length = 60)
    private String applicantName;

    @Column(length = 60)
    private String depositorName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PokemonOrderStatus status;

    /** 상품 합계 + {@link #extraInr}. 이체 금액의 근거가 되는 루피 총액이다. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalInr;

    /** 상품표에 없는 것을 위한 자유 입력 루피. 없으면 0. */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal extraInr;

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

    /** 실제 결제 시점의 환율. PREPARING 으로 처음 넘어갈 때 한 번 박히고 이후 환차손익이 확정된다. */
    @Column(precision = 14, scale = 6)
    private BigDecimal settledRate;

    @Column
    private LocalDateTime settledAt;

    @Column(length = 500)
    private String memo;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    private PokemonOrders(Long memberId, String applicantName, String depositorName,
                          PokemonOrderStatus status, BigDecimal totalInr, BigDecimal extraInr,
                          BigDecimal quotedRate,
                          LocalDateTime quotedAt, long itemsKrw, long donationKrw, long transferKrw,
                          String memo) {
        this.memberId = memberId;
        this.applicantName = applicantName;
        this.depositorName = depositorName;
        this.status = status;
        this.totalInr = totalInr;
        this.extraInr = extraInr;
        this.quotedRate = quotedRate;
        this.quotedAt = quotedAt;
        this.itemsKrw = itemsKrw;
        this.donationKrw = donationKrw;
        this.transferKrw = transferKrw;
        this.memo = memo;
    }

    public static PokemonOrders create(Long memberId, String applicantName, String depositorName,
                                       BigDecimal totalInr, BigDecimal extraInr,
                                       BigDecimal quotedRate, LocalDateTime quotedAt,
                                       long itemsKrw, long donationKrw, String memo) {
        return PokemonOrders.builder()
                .memberId(memberId)
                .applicantName(applicantName)
                .depositorName(depositorName)
                .status(PokemonOrderStatus.ORDERED)
                .totalInr(totalInr)
                .extraInr(extraInr)
                .quotedRate(quotedRate)
                .quotedAt(quotedAt)
                .itemsKrw(itemsKrw)
                .donationKrw(donationKrw)
                .transferKrw(itemsKrw + donationKrw)
                .memo(memo)
                .build();
    }

    /**
     * PREPARING(= 실제로 결제하는 단계)으로 <b>처음</b> 넘어갈 때만 결제 환율을 박는다. 되돌렸다가 다시 넘어가도
     * 최초 값을 유지한다 — 확정된 손익이 상태를 만질 때마다 움직이면 근거가 못 된다.
     */
    /**
     * 수정 — 금액을 통째로 다시 세운다. 주문 내용이 바뀌면 이체할 금액도 바뀌므로 환율
     * 스냅샷도 함께 갱신한다. 그래야 {@code quotedRate} 가 계속 "이 금액의 근거" 로 남는다.
     *
     * <p>{@code settledRate} 는 건드리지 않는다 — 이미 결제한 건의 확정 손익은 나중에
     * 신청서를 고쳤다고 움직이면 안 된다.
     */
    public void reprice(String applicantName, String depositorName, BigDecimal totalInr,
                        BigDecimal extraInr, BigDecimal quotedRate, LocalDateTime quotedAt,
                        long itemsKrw, long donationKrw, String memo) {
        this.applicantName = applicantName;
        this.depositorName = depositorName;
        this.totalInr = totalInr;
        this.extraInr = extraInr;
        this.quotedRate = quotedRate;
        this.quotedAt = quotedAt;
        this.itemsKrw = itemsKrw;
        this.donationKrw = donationKrw;
        this.transferKrw = itemsKrw + donationKrw;
        this.memo = memo;
    }

    public void changeStatus(PokemonOrderStatus next, BigDecimal currentRate, LocalDateTime now) {
        this.status = next;
        if (next == PokemonOrderStatus.PREPARING && settledRate == null) {
            this.settledRate = currentRate;
            this.settledAt = now;
        }
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
