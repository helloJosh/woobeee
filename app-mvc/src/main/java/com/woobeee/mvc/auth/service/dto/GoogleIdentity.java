package com.woobeee.mvc.auth.service.dto;

public record GoogleIdentity(
        String subject,
        String email,
        String name
) {
}
