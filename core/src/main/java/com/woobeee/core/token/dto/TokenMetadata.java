package com.woobeee.core.token.dto;

public record TokenMetadata(
        Long memberId,
        String role,
        String device,
        String ip
) {
}
