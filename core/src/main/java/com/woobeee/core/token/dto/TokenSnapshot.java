package com.woobeee.core.token.dto;

public record TokenSnapshot(
        TokenMetadata metadata,
        long ttlSeconds
) {
}
