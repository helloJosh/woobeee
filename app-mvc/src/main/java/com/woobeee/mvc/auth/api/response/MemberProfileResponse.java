package com.woobeee.mvc.auth.api.response;

/**
 * 내 프로필. 프로필 이미지는 URL 이 아니라 <b>존재 여부</b>만 알린다.
 *
 * <p>이미지는 인증이 필요한 {@code GET /api/auth/me/profile-image} 로 따로 받는다 —
 * {@code <img>} 태그는 Authorization 헤더를 보낼 수 없으므로 프론트가 fetch 로 받아 blob URL
 * 을 만든다. 전에 여기 있던 presigned URL 은 호스트가 서버용 {@code S3_ENDPOINT} 에서 나와
 * 브라우저가 열 수 없는 값이었다.
 */
public record MemberProfileResponse(
        Long memberId,
        String email,
        String nickname,
        long gameMoney,
        boolean hasProfileImage
) {
}
