package com.woobeee.mvc.auth.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "buyers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Buyer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String googleSubject;

    @Column(nullable = false, unique = true, length = 255)
    private String email;

    @Column(nullable = false, length = 60)
    private String nickname;

    @Column(nullable = false)
    private boolean termsAgreed;

    @Column(nullable = false)
    private boolean privacyPolicyAgreed;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private Buyer(
            String googleSubject,
            String email,
            String nickname,
            boolean termsAgreed,
            boolean privacyPolicyAgreed,
            boolean active,
            LocalDateTime createdAt
    ) {
        this.googleSubject = googleSubject;
        this.email = email;
        this.nickname = nickname;
        this.termsAgreed = termsAgreed;
        this.privacyPolicyAgreed = privacyPolicyAgreed;
        this.active = active;
        this.createdAt = createdAt;
    }

    public static Buyer create(
            String googleSubject,
            String email,
            String nickname,
            boolean termsAgreed,
            boolean privacyPolicyAgreed
    ) {
        return Buyer.builder()
                .googleSubject(googleSubject)
                .email(email)
                .nickname(nickname)
                .termsAgreed(termsAgreed)
                .privacyPolicyAgreed(privacyPolicyAgreed)
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
