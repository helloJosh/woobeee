package com.woobeee.mvc.auth.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "members")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member {
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
    private String profileImageKey;

    /** 일정 다이제스트를 받을 Slack Incoming Webhook URL. NULL = 알림 미사용. */
    @Column(length = 500)
    private String slackWebhookUrl;

    @Column(nullable = false)
    private long gameMoney;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MemberRole role;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Builder
    private Member(
            String googleSubject,
            String email,
            String nickname,
            boolean termsAgreed,
            boolean privacyPolicyAgreed,
            String profileImageKey,
            long gameMoney,
            MemberRole role,
            boolean active,
            LocalDateTime createdAt
    ) {
        this.googleSubject = googleSubject;
        this.email = email;
        this.nickname = nickname;
        this.termsAgreed = termsAgreed;
        this.privacyPolicyAgreed = privacyPolicyAgreed;
        this.profileImageKey = profileImageKey;
        this.gameMoney = gameMoney;
        this.role = role;
        this.active = active;
        this.createdAt = createdAt;
    }

    public static Member create(
            String googleSubject,
            String email,
            String nickname,
            boolean termsAgreed,
            boolean privacyPolicyAgreed
    ) {
        return Member.builder()
                .googleSubject(googleSubject)
                .email(email)
                .nickname(nickname)
                .termsAgreed(termsAgreed)
                .privacyPolicyAgreed(privacyPolicyAgreed)
                .profileImageKey(null)
                .gameMoney(0L)
                .role(MemberRole.ROLE_MEMBER)
                .active(true)
                .createdAt(LocalDateTime.now())
                .build();
    }

    public void changeProfileImageKey(String profileImageKey) {
        this.profileImageKey = profileImageKey;
    }

    public void removeProfileImageKey() {
        this.profileImageKey = null;
    }

    public void changeSlackWebhookUrl(String slackWebhookUrl) {
        this.slackWebhookUrl = slackWebhookUrl;
    }

    public void removeSlackWebhookUrl() {
        this.slackWebhookUrl = null;
    }
}
