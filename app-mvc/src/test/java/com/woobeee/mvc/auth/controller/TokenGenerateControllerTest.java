package com.woobeee.mvc.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.woobeee.mvc.auth.api.request.TokenIssueRequest;
import com.woobeee.mvc.auth.api.request.TokenRefreshRequest;
import com.woobeee.mvc.auth.api.response.TokenResponse;
import com.woobeee.mvc.auth.exception.AuthRestControllerAdvice;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.auth.service.TokenService;
import com.woobeee.core.token.TokenStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TokenGenerateController.class)
@Import(AuthRestControllerAdvice.class)
class TokenGenerateControllerTest {
    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private TokenService tokenService;

    @MockitoBean
    private TokenStore tokenStore;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void issueReturnsTokenResponse() throws Exception {
        TokenIssueRequest request = new TokenIssueRequest(7L, "ROLE_MEMBER", "web");
        TokenResponse tokenResponse = new TokenResponse("issued-access", 900, "issued-refresh", 2_592_000, 7L, "ROLE_MEMBER");
        when(tokenService.issue(7L, "ROLE_MEMBER", "web", "192.0.2.10")).thenReturn(tokenResponse);

        mockMvc.perform(post("/api/auth/access-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Real-IP", "192.0.2.10")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.header.message").value("Token issued"))
                .andExpect(jsonPath("$.data.accessToken").value("issued-access"))
                .andExpect(jsonPath("$.data.refreshToken").value("issued-refresh"))
                .andExpect(jsonPath("$.data.memberId").value(7))
                .andExpect(jsonPath("$.data.role").value("ROLE_MEMBER"));

        verify(tokenService).issue(7L, "ROLE_MEMBER", "web", "192.0.2.10");
    }

    @Test
    void refreshReturnsTokenResponse() throws Exception {
        TokenRefreshRequest request = new TokenRefreshRequest("refresh-token", "ios");
        TokenResponse tokenResponse = new TokenResponse("new-access", 900, "new-refresh", 2_592_000, 9L, "ROLE_MEMBER");
        when(tokenService.refresh("refresh-token", "ios", "203.0.113.20")).thenReturn(tokenResponse);

        mockMvc.perform(post("/api/auth/refresh-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Forwarded-For", "203.0.113.20")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.header.message").value("Token refreshed"))
                .andExpect(jsonPath("$.data.accessToken").value("new-access"))
                .andExpect(jsonPath("$.data.refreshToken").value("new-refresh"))
                .andExpect(jsonPath("$.data.memberId").value(9))
                .andExpect(jsonPath("$.data.role").value("ROLE_MEMBER"));

        verify(tokenService).refresh("refresh-token", "ios", "203.0.113.20");
    }

    @Test
    void refreshRejectsInvalidRequestBody() throws Exception {
        TokenRefreshRequest request = new TokenRefreshRequest(" ", "ios");

        mockMvc.perform(post("/api/auth/refresh-tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.message").value("Refresh token is required"));

        verifyNoInteractions(tokenService);
    }
}
