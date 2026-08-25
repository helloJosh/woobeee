package com.woobeee.mvc.auth.controller;

import com.woobeee.core.token.TokenStore;
import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.core.token.dto.TokenSnapshot;
import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.api.response.MemberProfileResponse;
import com.woobeee.mvc.auth.exception.AuthRestControllerAdvice;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.auth.service.MemberProfileImageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemberProfileImageController.class)
@Import(AuthRestControllerAdvice.class)
class MemberProfileImageControllerTest {
    private static final String LOGIN_ID = "member@example.com";
    private static final String ACCESS_TOKEN = "access-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MemberProfileImageService memberProfileImageService;

    @MockitoBean
    private TokenStore tokenStore;

    @MockitoBean
    private MemberRepository memberRepository;

    /** loginId 헤더는 필터가 토큰에서 파생해 주입한다 — 테스트도 토큰 경로로 신원을 만든다. */
    private void authenticate() {
        when(tokenStore.find(ACCESS_TOKEN, AuthTokenType.ACCESS))
                .thenReturn(Optional.of(new TokenSnapshot(
                        new TokenMetadata(42L, "ROLE_MEMBER", "web", "127.0.0.1"), 900L)));
        Member member = Member.create("google-sub", LOGIN_ID, "nick", true, true);
        ReflectionTestUtils.setField(member, "id", 42L);
        when(memberRepository.findById(42L)).thenReturn(Optional.of(member));
    }

    /** AUTH-AC-14 — 프로필은 이미지의 <b>공개 URL</b> 을 내린다. 미설정이면 null 이다. */
    @Test
    void getMyProfileReportsThatAProfileImageExists() throws Exception {
        authenticate();
        when(memberProfileImageService.getMyProfile(LOGIN_ID))
                .thenReturn(new MemberProfileResponse(42L, LOGIN_ID, "nick", 0L, "/api/auth/members/42/profile-image"));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.data.memberId").value(42))
                .andExpect(jsonPath("$.data.email").value(LOGIN_ID))
                .andExpect(jsonPath("$.data.nickname").value("nick"))
                .andExpect(jsonPath("$.data.gameMoney").value(0))
                .andExpect(jsonPath("$.data.profileImageUrl").value("/api/auth/members/42/profile-image"))
                // 상대 경로여야 한다. 절대 URL 이 돌아오면(presigned 든 도메인 하드코딩이든)
                // 로컬/프로덕션 중 한쪽이 깨진다.
                .andExpect(jsonPath("$.data.profileImageUrl").value(org.hamcrest.Matchers.startsWith("/")));
    }

    /** AUTH-AC-14 */
    @Test
    void getMyProfileReportsNoImageWhenUnset() throws Exception {
        authenticate();
        when(memberProfileImageService.getMyProfile(LOGIN_ID))
                .thenReturn(new MemberProfileResponse(42L, LOGIN_ID, "nick", 0L, null));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").doesNotExist());
    }

    @Test
    void getMyProfileWithoutLoginIdHeaderIsUnauthorized() throws Exception {
        when(memberProfileImageService.getMyProfile(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication is required"));

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    /** AUTH-AC-10 — 업로드는 multipart `file` 파트를 받아 갱신된 프로필을 돌려준다. */
    @Test
    void uploadAcceptsAMultipartFileAndReturnsTheUpdatedProfile() throws Exception {
        authenticate();
        when(memberProfileImageService.upload(eq(LOGIN_ID), any()))
                .thenReturn(new MemberProfileResponse(42L, LOGIN_ID, "nick", 0L, "/api/auth/members/42/profile-image"));

        mockMvc.perform(multipart("/api/auth/me/profile-image")
                        .file(new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2, 3}))
                        .header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.data.profileImageUrl").value("/api/auth/members/42/profile-image"));
    }

    /**
     * AUTH-AC-18 — 스트리밍은 {@code ApiResponse} 봉투를 타지 않는다. 봉투에 실으면
     * {@code <img>} 가 읽을 수 없는 JSON 이 된다.
     */
    @Test
    void profileImageStreamsRawBytesOutsideTheEnvelope() throws Exception {
        when(memberProfileImageService.loadProfileImage(42L))
                .thenReturn(new MemberProfileImageService.ProfileImage(new byte[]{1, 2, 3}, "image/png", "abc123"));

        mockMvc.perform(get("/api/auth/members/42/profile-image"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
    }

    /**
     * AUTH-AC-20 — <b>토큰 없이도</b> 조회된다. 아바타는 남이 봐야 하는 이미지다 -- 인증을
     * 요구하면 {@code <img>} 가 Authorization 헤더를 못 보내므로 댓글 작성자 아바타를 그릴 수
     * 없다. 이 테스트가 인증 요구가 되돌아오는 것을 막는다.
     */
    @Test
    void anyoneCanReadAProfileImageWithoutAToken() throws Exception {
        when(memberProfileImageService.loadProfileImage(42L))
                .thenReturn(new MemberProfileImageService.ProfileImage(new byte[]{9}, "image/png", "abc123"));

        mockMvc.perform(get("/api/auth/members/42/profile-image"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{9}))
                // 공개 리소스이므로 공유 캐시에 남아도 된다. no-store 로 되돌아오면 잡는다.
                .andExpect(header().string("Cache-Control", "max-age=300, public"))
                .andExpect(header().string("ETag", "\"abc123\""));
    }

    /**
     * AUTH-AC-20 — ETag 가 같으면 304 다. 오브젝트 키에 UUID 가 있어 이미지를 교체하면 ETag 가
     * 반드시 바뀌므로, 캐시가 낡은 아바타를 붙들고 있지 않는다.
     */
    @Test
    void anUnchangedProfileImageAnswersNotModified() throws Exception {
        when(memberProfileImageService.loadProfileImage(42L))
                .thenReturn(new MemberProfileImageService.ProfileImage(new byte[]{1}, "image/png", "abc123"));

        mockMvc.perform(get("/api/auth/members/42/profile-image").header("If-None-Match", "\"abc123\""))
                .andExpect(status().isNotModified());
    }

    /** AUTH-AC-19 */
    @Test
    void profileImageIsNotFoundWhenUnset() throws Exception {
        when(memberProfileImageService.loadProfileImage(42L))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile image is not set"));

        mockMvc.perform(get("/api/auth/members/42/profile-image"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        authenticate();
        mockMvc.perform(delete("/api/auth/me/profile-image").header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isNoContent());

        verify(memberProfileImageService).delete(LOGIN_ID);
    }
}
