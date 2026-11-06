package com.woobeee.mvc.pokemon.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 한 번의 공동구매. 여는 사람이 주최자이고, 통화·계좌·마감일을 차수가 들고 있다.
 *
 * <p>환율은 들고 있지 않는다. 원화는 하루 한 번 갱신되는 오늘 환율로 매번 계산한 "예상 금액"일
 * 뿐이고, 환차손익도 없다(V27).
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

    /** 주최자마다 계좌가 다르므로 차수가 들고 있다. */
    @Column(nullable = false, length = 200)
    private String bankAccount;

    private LocalDate deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PokemonRoundStatus status;

    @Column(length = 500)
    private String memo;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Builder
    private PokemonRounds(Long hostMemberId, int sequence, String title, String currency,
                          String bankAccount, LocalDate deadline, PokemonRoundStatus status, String memo) {
        this.hostMemberId = hostMemberId;
        this.sequence = sequence;
        this.title = title;
        this.currency = currency;
        this.bankAccount = bankAccount;
        this.deadline = deadline;
        this.status = status;
        this.memo = memo;
    }

    public static PokemonRounds open(Long hostMemberId, int sequence, String title, String currency,
                                     String bankAccount, LocalDate deadline, String memo) {
        return PokemonRounds.builder()
                .hostMemberId(hostMemberId).sequence(sequence).title(title).currency(currency)
                .bankAccount(bankAccount)
                .deadline(deadline).status(PokemonRoundStatus.OPEN).memo(memo)
                .build();
    }

    /** 통화는 고치지 않는다 — 이미 들어온 신청서가 그 통화의 상품으로 채워져 있다. */
    public void update(String title, String bankAccount, LocalDate deadline, String memo) {
        this.title = title;
        this.bankAccount = bankAccount;
        this.deadline = deadline;
        this.memo = memo;
    }

    public void changeStatus(PokemonRoundStatus next) {
        this.status = next;
    }

    public boolean isHostedBy(Long candidateMemberId) {
        return candidateMemberId != null && hostMemberId.equals(candidateMemberId);
    }
}
