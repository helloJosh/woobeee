package com.woobeee.mvc.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.woobeee.core.token.TokenStore;
import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.core.token.dto.TokenSnapshot;
import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.api.request.MemberProfileImagePresignedUrlRequest;
import com.woobeee.mvc.auth.api.request.MemberProfileImageRegisterRequest;
import com.woobeee.mvc.auth.api.response.MemberProfileImageResponse;
import com.woobeee.mvc.auth.api.response.MemberProfileImageUploadUrlResponse;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MemberProfileImageController.class)
@Import(AuthRestControllerAdvice.class)
class MemberProfileImageControllerTest {
    private static final String LOGIN_ID = "member@example.com";
    private static final String ACCESS_TOKEN = "access-token";

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    /** AUTH-AC-14 */
    @Test
    void getMyProfileReturnsPresignedGetUrl() throws Exception {
        authenticate();
        when(memberProfileImageService.getMyProfile(LOGIN_ID)).thenReturn(new MemberProfileResponse(
                42L,
                LOGIN_ID,
                "nick",
                0L,
                "https://s3.example.com/woobeee/profiles/42/uuid/avatar.png?sig=1"
        ));

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.data.memberId").value(42))
                .andExpect(jsonPath("$.data.email").value(LOGIN_ID))
                .andExpect(jsonPath("$.data.nickname").value("nick"))
                .andExpect(jsonPath("$.data.gameMoney").value(0))
                .andExpect(jsonPath("$.data.profileImageUrl")
                        .value("https://s3.example.com/woobeee/profiles/42/uuid/avatar.png?sig=1"));
    }

    /** AUTH-AC-14 */
    @Test
    void getMyProfileReturnsNullProfileImageUrlWhenUnset() throws Exception {
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

    @Test
    void createPresignedUploadUrlReturnsUploadTarget() throws Exception {
        authenticate();
        MemberProfileImagePresignedUrlRequest request =
                new MemberProfileImagePresignedUrlRequest("avatar.png", "image/png");
        when(memberProfileImageService.createPresignedUploadUrl(eq(LOGIN_ID), eq(request)))
                .thenReturn(new MemberProfileImageUploadUrlResponse(
                        "https://s3.example.com/upload",
                        "profiles/42/uuid/avatar.png",
                        600L
                ));

        mockMvc.perform(post("/api/auth/me/profile-image/presigned-url")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uploadUrl").value("https://s3.example.com/upload"))
                .andExpect(jsonPath("$.data.fileKey").value("profiles/42/uuid/avatar.png"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(600));
    }

    @Test
    void registerReturnsProfileImageUrl() throws Exception {
        authenticate();
        MemberProfileImageRegisterRequest request =
                new MemberProfileImageRegisterRequest("profiles/42/uuid/avatar.png");
        when(memberProfileImageService.register(eq(LOGIN_ID), eq(request)))
                .thenReturn(new MemberProfileImageResponse("https://s3.example.com/get"));

        mockMvc.perform(put("/api/auth/me/profile-image")
                        .header("Authorization", "Bearer " + ACCESS_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.profileImageUrl").value("https://s3.example.com/get"));
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        authenticate();
        mockMvc.perform(delete("/api/auth/me/profile-image").header("Authorization", "Bearer " + ACCESS_TOKEN))
                .andExpect(status().isNoContent());

        verify(memberProfileImageService).delete(LOGIN_ID);
    }
}
