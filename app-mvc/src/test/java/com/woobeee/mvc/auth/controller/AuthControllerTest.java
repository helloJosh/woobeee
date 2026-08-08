package com.woobeee.mvc.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.woobeee.mvc.auth.api.request.GoogleAuthorizationCallbackRequest;
import com.woobeee.mvc.auth.api.request.LoginRequest;
import com.woobeee.mvc.auth.api.request.MemberSignupRequest;
import com.woobeee.mvc.auth.api.response.GoogleAuthorizationResponse;
import com.woobeee.mvc.auth.api.response.TokenResponse;
import com.woobeee.mvc.auth.exception.AuthRestControllerAdvice;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.auth.service.AuthService;
import com.woobeee.core.token.TokenStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import(AuthRestControllerAdvice.class)
class AuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private TokenStore tokenStore;

    @MockitoBean
    private MemberRepository memberRepository;

    @Test
    void signupReturnsAuthorizationResponse() throws Exception {
        MemberSignupRequest request = new MemberSignupRequest("member-nick", true, true, "ios");
        GoogleAuthorizationResponse response = new GoogleAuthorizationResponse(
                "https://accounts.google.com/o/oauth2/v2/auth?state=state-123",
                "state-123",
                600
        );
        when(authService.signup(eq(request))).thenReturn(response);

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.header.message").value("Member signup authorization created"))
                .andExpect(jsonPath("$.data.authorizationUrl")
                        .value("https://accounts.google.com/o/oauth2/v2/auth?state=state-123"))
                .andExpect(jsonPath("$.data.state").value("state-123"));

        verify(authService).signup(request);
    }

    @Test
    void loginReturnsAuthorizationResponse() throws Exception {
        LoginRequest request = new LoginRequest("android");
        GoogleAuthorizationResponse response = new GoogleAuthorizationResponse(
                "https://accounts.google.com/o/oauth2/v2/auth?state=login-state",
                "login-state",
                600
        );
        when(authService.login(eq(request))).thenReturn(response);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.header.message").value("Login authorization created"))
                .andExpect(jsonPath("$.data.state").value("login-state"));

        verify(authService).login(request);
    }

    @Test
    void completeGoogleAuthorizationReturnsTokenResponse() throws Exception {
        GoogleAuthorizationCallbackRequest request = new GoogleAuthorizationCallbackRequest("auth-code", "state-123");
        TokenResponse tokenResponse = new TokenResponse("access-77", 900, "refresh-77", 2_592_000, 77L, "ROLE_MEMBER");
        when(authService.completeGoogleAuthorization(eq(request), eq("10.0.0.5"))).thenReturn(tokenResponse);

        mockMvc.perform(post("/api/auth/callback-google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Real-IP", "10.0.0.5")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.header.isSuccessful").value(true))
                .andExpect(jsonPath("$.header.message").value("Google authorization completed"))
                .andExpect(jsonPath("$.data.accessToken").value("access-77"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-77"))
                .andExpect(jsonPath("$.data.memberId").value(77))
                .andExpect(jsonPath("$.data.role").value("ROLE_MEMBER"));

        verify(authService).completeGoogleAuthorization(request, "10.0.0.5");
    }

    @Test
    void signupRejectsInvalidRequestBody() throws Exception {
        MemberSignupRequest request = new MemberSignupRequest(" ", true, true, "ios");

        mockMvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.header.isSuccessful").value(false))
                .andExpect(jsonPath("$.header.message").value("Nickname is required"));

        verifyNoInteractions(authService);
    }
}
