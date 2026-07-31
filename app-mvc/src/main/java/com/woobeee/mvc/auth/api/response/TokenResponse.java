package com.woobeee.mvc.auth.api.response;

public record TokenResponse(
        String accessToken,
        long accessTokenExpiresInSeconds,
        String refreshToken,
        long refreshTokenExpiresInSeconds,
        Long memberId,
        String role
) {
}
