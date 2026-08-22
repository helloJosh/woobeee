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

    /** AUTH-AC-14 — 프로필은 이미지 URL 이 아니라 보유 여부를 내린다. */
    @Test
    void getMyProfileReportsThatAProfileImageExists() throws Exception {
        authenticate();
        when(memberProfileImageService.getMyProfile(LOGIN_ID))
                .thenReturn(new MemberProfileResponse(42L, LOGIN_ID, "nick", 0L, true));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.data.memberId").value(42))
                .andExpect(jsonPath("$.data.email").value(LOGIN_ID))
                .andExpect(jsonPath("$.data.nickname").value("nick"))
                .andExpect(jsonPath("$.data.gameMoney").value(0))
                .andExpect(jsonPath("$.data.hasProfileImage").value(true))
                // 브라우저가 못 여는 presigned URL 이 계약으로 되돌아오면 잡는다.
                .andExpect(jsonPath("$.data.profileImageUrl").doesNotExist());
    }

    /** AUTH-AC-14 */
    @Test
    void getMyProfileReportsNoImageWhenUnset() throws Exception {
        authenticate();
        when(memberProfileImageService.getMyProfile(LOGIN_ID))
                .thenReturn(new MemberProfileResponse(42L, LOGIN_ID, "nick", 0L, false));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasProfileImage").value(false));
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
                .thenReturn(new MemberProfileResponse(42L, LOGIN_ID, "nick", 0L, true));

        mockMvc.perform(multipart("/api/auth/me/profile-image")
                        .file(new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2, 3}))
                        .header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.data.hasProfileImage").value(true));
    }

    /**
     * AUTH-AC-18 — 스트리밍은 {@code ApiResponse} 봉투를 타지 않는다. 봉투에 실으면
     * {@code <img>}/blob 이 읽을 수 없는 JSON 이 된다.
     */
    @Test
    void getMyProfileImageStreamsRawBytesOutsideTheEnvelope() throws Exception {
        authenticate();
        when(memberProfileImageService.loadMyProfileImage(LOGIN_ID))
                .thenReturn(new MemberProfileImageService.ProfileImage(new byte[]{1, 2, 3}, "image/png"));

        mockMvc.perform(get("/api/auth/me/profile-image").header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}))
                // 본인 전용 리소스이므로 공유 캐시에 남으면 안 된다.
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    /** AUTH-AC-19 */
    @Test
    void getMyProfileImageIsNotFoundWhenUnset() throws Exception {
        authenticate();
        when(memberProfileImageService.loadMyProfileImage(LOGIN_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Profile image is not set"));

        mockMvc.perform(get("/api/auth/me/profile-image").header("Authorization", "Bearer " + ACCESS_TOKEN))
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
