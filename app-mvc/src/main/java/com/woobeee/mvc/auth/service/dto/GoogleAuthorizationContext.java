package com.woobeee.mvc.auth.service.dto;

public record GoogleAuthorizationContext(
        GoogleAuthorizationAction action,
        String codeVerifier,
        String device,
        String nickname,
        boolean termsAgreed,
        boolean privacyPolicyAgreed
) {
}
