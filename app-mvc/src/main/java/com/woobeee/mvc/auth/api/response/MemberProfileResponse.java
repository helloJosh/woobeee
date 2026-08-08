package com.woobeee.mvc.auth.api.response;

public record MemberProfileResponse(
        Long memberId,
        String email,
        String nickname,
        long gameMoney,
        String profileImageUrl
) {
}
