package com.woobeee.mvc.auth.api.response;

/**
 * 내 프로필. 프로필 이미지는 <b>공개 URL</b> 로 알린다.
 *
 * <p>전에는 {@code hasProfileImage} 불리언만 주고 프론트가 인증이 필요한
 * {@code GET /me/profile-image} 를 fetch 해 blob URL 을 만들었다. {@code <img>} 가
 * Authorization 헤더를 못 보내기 때문이었는데, 그 구조로는 <b>남의 아바타를 그릴 수 없다</b> --
 * 댓글 작성자 아바타를 붙이는 순간 막힌다.
 *
 * <p>그래서 이미지 조회를 공개로 바꿨다. 아바타는 본질적으로 남이 봐야 하는 이미지이고,
 * 오브젝트 키에 UUID 가 들어 있어 열거되지 않는다. 이제 {@code <img src={profileImageUrl}>}
 * 로 끝난다.
 *
 * @param profileImageUrl 프로필 이미지 주소. 미설정이면 {@code null}
 */
public record MemberProfileResponse(
        Long memberId,
        String email,
        String nickname,
        long gameMoney,
        String profileImageUrl
) {
}
