package com.woobeee.mvc.pokemon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 한 번의 공동구매. 여는 사람이 주최자이고, 통화·환율·계좌·마감일을 차수가 들고 있다.
 *
 * <p>환율을 <b>열 때 한 번</b> 박는 것이 이 설계의 핵심이다. 주최자는 마감 뒤 한 번에
 * 결제하므로, 한 차수 안에서는 모두 같은 환율로 계산해야 "왜 나만 더 내요" 가 나오지 않는다.
 * 실제 결제 시점의 환율은 {@code settledRate} 에 따로 박혀 환차손익이 확정된다.
 */
@Getter
@Entity
@Table(name = "pokemon_rounds")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PokemonRounds {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long hostMemberId;

    /** 주최자 안에서 1차, 2차, 3차. 주최자가 다르면 번호가 겹쳐도 된다. */
    @Column(nullable = false)
    private int sequence;

    @Column(length = 100)
    private String title;

    /** ISO 4217. 인도 스토어면 INR, 미국이면 USD. */
    @Column(nullable = false, length = 3)
    private String currency;

    /** 환율을 어떻게 잡을지. 주최자가 차수를 열 때 고른다. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PokemonRateMode rateMode;

    /**
     * 1 {@link #currency} = N KRW. {@link PokemonRateMode#FIXED} 일 때 쓰는 값이다.
     * 주최자가 고칠 수 있지만, 고쳐도 이미 들어온 신청서는 각자 박아 둔 환율을 유지한다.
     */
    @Column(nullable = false, precision = 14, scale = 6)
    private BigDecimal quotedRate;

    @Column(nullable = false)
    private LocalDateTime quotedAt;

    /** 주최자마다 계좌가 다르므로 차수가 들고 있다. */
    @Column(nullable = false, length = 200)
    private String bankAccount;

    private LocalDate deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PokemonRoundStatus status;

    /** 실제 결제 시점의 환율. PURCHASED 로 처음 넘어갈 때 한 번 박힌다. */
    @Column(precision = 14, scale = 6)
    private BigDecimal settledRate;

    private LocalDateTime settledAt;

    @Column(length = 500)
    private String memo;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    private PokemonRounds(Long hostMemberId, int sequence, String title, String currency,
                          PokemonRateMode rateMode, BigDecimal quotedRate, LocalDateTime quotedAt, String bankAccount,
                          LocalDate deadline, PokemonRoundStatus status, String memo) {
        this.hostMemberId = hostMemberId;
        this.sequence = sequence;
        this.title = title;
        this.currency = currency;
        this.rateMode = rateMode;
        this.quotedRate = quotedRate;
        this.quotedAt = quotedAt;
        this.bankAccount = bankAccount;
        this.deadline = deadline;
        this.status = status;
        this.memo = memo;
    }

    public static PokemonRounds open(Long hostMemberId, int sequence, String title, String currency,
                                     PokemonRateMode rateMode, BigDecimal quotedRate,
                                     LocalDateTime quotedAt, String bankAccount,
                                     LocalDate deadline, String memo) {
        return PokemonRounds.builder()
                .hostMemberId(hostMemberId).sequence(sequence).title(title).currency(currency)
                .rateMode(rateMode).quotedRate(quotedRate).quotedAt(quotedAt).bankAccount(bankAccount)
                .deadline(deadline).status(PokemonRoundStatus.OPEN).memo(memo)
                .build();
    }

    /**
     * 통화는 고치지 않는다 — 이미 들어온 신청서가 그 통화의 상품으로 채워져 있다.
     * 환율은 고칠 수 있지만 <b>앞으로 들어올 신청서에만</b> 적용된다. 이미 알려 준 이체
     * 금액이 나중에 달라지면 안 되므로, 기존 신청서는 각자 박아 둔 환율을 유지한다.
     */
    public void update(String title, String bankAccount, LocalDate deadline, String memo,
                       PokemonRateMode rateMode, BigDecimal quotedRate, LocalDateTime quotedAt) {
        this.title = title;
        this.bankAccount = bankAccount;
        this.deadline = deadline;
        this.memo = memo;
        this.rateMode = rateMode;
        this.quotedRate = quotedRate;
        this.quotedAt = quotedAt;
    }

    /** 이 차수에서 지금 신청하면 쓸 환율. PER_ORDER 면 넘겨받은 현재 환율을 쓴다. */
    public BigDecimal rateFor(BigDecimal currentRate) {
        return rateMode == PokemonRateMode.PER_ORDER ? currentRate : quotedRate;
    }

    /**
     * PURCHASED 로 <b>처음</b> 넘어갈 때만 결제 환율을 박는다. 되돌렸다 다시 넘어가도 최초
     * 값을 유지한다 — 확정된 손익이 상태를 만질 때마다 움직이면 근거가 못 된다.
     */
    public void changeStatus(PokemonRoundStatus next, BigDecimal currentRate, LocalDateTime now) {
        this.status = next;
        if (next == PokemonRoundStatus.PURCHASED && settledRate == null) {
            this.settledRate = currentRate;
            this.settledAt = now;
        }
    }

    public boolean isHostedBy(Long candidateMemberId) {
        return candidateMemberId != null && hostMemberId.equals(candidateMemberId);
    }
}
