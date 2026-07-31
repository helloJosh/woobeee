package com.woobeee.core.token.dto;

import java.time.Duration;

public enum AuthTokenType {
    ACCESS(Duration.ofMinutes(15)),
    REFRESH(Duration.ofDays(30));

    private final Duration ttl;

    AuthTokenType(Duration ttl) {
        this.ttl = ttl;
    }

    public Duration ttl() {
        return ttl;
    }

    public String redisKey(String token) {
        return "auth:token:" + name().toLowerCase() + ":" + token;
    }

    public String reverseKey(Long memberId, String device) {
        return "auth:user-token:" + name().toLowerCase() + ":" + memberId + ":" + device;
    }
}
