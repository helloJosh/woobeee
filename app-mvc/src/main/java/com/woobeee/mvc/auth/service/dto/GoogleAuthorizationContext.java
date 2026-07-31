package com.woobeee.mvc.auth.service.dto;

import com.woobeee.mvc.auth.entity.MemberType;

public record GoogleAuthorizationContext(
        GoogleAuthorizationAction action,
        String codeVerifier,
        String device,
        MemberType memberType,
        String nickname,
        boolean termsAgreed,
        boolean privacyPolicyAgreed,
        String businessRegistrationCertificateUrl
) {
}
