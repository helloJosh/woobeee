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
@Table(name = "sellers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Seller {
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

    @Column(length = 1000)
    private String businessRegistrationCertificateUrl;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private Seller(
            String googleSubject,
            String email,
            String nickname,
            boolean termsAgreed,
            boolean privacyPolicyAgreed,
            String businessRegistrationCertificateUrl,
            boolean active,
            LocalDateTime createdAt
    ) {
        this.googleSubject = googleSubject;
        this.email = email;
        this.nickname = nickname;
        this.termsAgreed = termsAgreed;
        this.privacyPolicyAgreed = privacyPolicyAgreed;
        this.businessRegistrationCertificateUrl = businessRegistrationCertificateUrl;
        this.active = active;
        this.createdAt = createdAt;
    }

    public static Seller create(
            String googleSubject,
            String email,
            String nickname,
            boolean termsAgreed,
            boolean privacyPolicyAgreed,
            String businessRegistrationCertificateUrl
    ) {
        return Seller.builder()
                .googleSubject(googleSubject)
                .email(email)
                .nickname(nickname)
                .termsAgreed(termsAgreed)
                .privacyPolicyAgreed(privacyPolicyAgreed)
                .businessRegistrationCertificateUrl(businessRegistrationCertificateUrl)
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
