package com.woobeee.mvc.auth.api.response;

public record GoogleAuthorizationResponse(
        String authorizationUrl,
        String state,
        long expiresInSeconds
) {
}
