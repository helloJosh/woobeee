package com.woobeee.mvc.auth.service;

import com.woobeee.mvc.auth.api.request.GoogleAuthorizationCallbackRequest;
import com.woobeee.mvc.auth.api.request.LoginRequest;
import com.woobeee.mvc.auth.api.request.MemberSignupRequest;
import com.woobeee.mvc.auth.api.response.GoogleAuthorizationResponse;
import com.woobeee.mvc.auth.api.response.TokenResponse;
import com.woobeee.mvc.auth.config.GoogleOauthProperties;
import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.entity.MemberRole;
import com.woobeee.mvc.auth.repository.MemberRepository;
import com.woobeee.mvc.auth.service.dto.GoogleAuthorizationAction;
import com.woobeee.mvc.auth.service.dto.GoogleAuthorizationContext;
import com.woobeee.mvc.auth.service.dto.GoogleIdentity;
import com.woobeee.mvc.auth.service.dto.GoogleTokenExchangeResponse;
import com.woobeee.core.token.TokenGenerator;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock
    private MemberRepository memberRepository;

    @Mock
    private GoogleIdentityVerifier googleIdentityVerifier;

    @Mock
    private GoogleOauthClient googleOauthClient;

    @Mock
    private GoogleAuthorizationStateStore googleAuthorizationStateStore;

    @Mock
    private GoogleOauthProperties googleOauthProperties;

    @Mock
    private TokenGenerator tokenGenerator;

    @Mock
    private TokenService tokenService;

    @InjectMocks
    private AuthService authService;

    private void mockAuthorizationProperties() {
        when(googleOauthProperties.getClientId()).thenReturn("google-client-id");
        when(googleOauthProperties.getRedirectUri()).thenReturn("http://localhost:3000/auth/google/callback");
        when(googleOauthProperties.getAuthorizationUri()).thenReturn("https://accounts.google.com/o/oauth2/v2/auth");
        when(googleOauthProperties.getScope()).thenReturn("openid email profile");
        when(googleOauthProperties.getAuthorizationStateTtlSeconds()).thenReturn(600L);
    }

    private GoogleTokenExchangeResponse tokenExchangeResponse() {
        return new GoogleTokenExchangeResponse(
                "google-access",
                3600L,
                null,
                "openid email profile",
                "Bearer",
                "id-token"
        );
    }

    @Test
    void signupCreatesAuthorizationResponseAndStoresContext() {
        mockAuthorizationProperties();
        MemberSignupRequest request = new MemberSignupRequest("member-nick", true, true, "ios");
        when(tokenGenerator.nextToken()).thenReturn("state-123");

        GoogleAuthorizationResponse response = authService.signup(request);

        ArgumentCaptor<GoogleAuthorizationContext> contextCaptor =
                ArgumentCaptor.forClass(GoogleAuthorizationContext.class);
        verify(googleAuthorizationStateStore).save(eq("state-123"), contextCaptor.capture());

        GoogleAuthorizationContext context = contextCaptor.getValue();
        assertThat(context.action()).isEqualTo(GoogleAuthorizationAction.SIGNUP);
        assertThat(context.device()).isEqualTo("ios");
        assertThat(context.nickname()).isEqualTo("member-nick");
        assertThat(context.codeVerifier()).isNotBlank();

        assertThat(response.state()).isEqualTo("state-123");
        assertThat(response.expiresInSeconds()).isEqualTo(600L);
        assertThat(response.authorizationUrl()).contains("response_type=code");
        assertThat(response.authorizationUrl()).contains("client_id=google-client-id");
        assertThat(response.authorizationUrl()).contains("state=state-123");
        assertThat(response.authorizationUrl()).contains("code_challenge_method=S256");
    }

    @Test
    void loginCreatesAuthorizationResponseAndStoresContext() {
        mockAuthorizationProperties();
        LoginRequest request = new LoginRequest("android");
        when(tokenGenerator.nextToken()).thenReturn("login-state");

        GoogleAuthorizationResponse response = authService.login(request);

        ArgumentCaptor<GoogleAuthorizationContext> contextCaptor =
                ArgumentCaptor.forClass(GoogleAuthorizationContext.class);
        verify(googleAuthorizationStateStore).save(eq("login-state"), contextCaptor.capture());

        GoogleAuthorizationContext context = contextCaptor.getValue();
        assertThat(context.action()).isEqualTo(GoogleAuthorizationAction.LOGIN);
        assertThat(context.device()).isEqualTo("android");
        assertThat(response.state()).isEqualTo("login-state");
    }

    /** AUTH-AC-08 */
    @Test
    void completeGoogleAuthorizationSignsUpMemberAndIssuesTokens() {
        GoogleAuthorizationCallbackRequest request = new GoogleAuthorizationCallbackRequest("auth-code", "state-123");
        GoogleAuthorizationContext context = new GoogleAuthorizationContext(
                GoogleAuthorizationAction.SIGNUP,
                "code-verifier",
                "ios",
                "member-nick",
                true,
                true
        );
        when(googleAuthorizationStateStore.find("state-123")).thenReturn(Optional.of(context));
        when(googleOauthClient.exchangeAuthorizationCode("auth-code", "code-verifier"))
                .thenReturn(tokenExchangeResponse());
        when(googleIdentityVerifier.verify("id-token"))
                .thenReturn(new GoogleIdentity("google-sub", "member@example.com", "Member Name"));
        when(memberRepository.existsByGoogleSubject("google-sub")).thenReturn(false);
        when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            ReflectionTestUtils.setField(member, "id", 11L);
            return member;
        });
        when(tokenService.issue(11L, "ROLE_MEMBER", "ios", "127.0.0.1"))
                .thenReturn(new TokenResponse("access", 900, "refresh", 2_592_000, 11L, "ROLE_MEMBER"));

        TokenResponse response = authService.completeGoogleAuthorization(request, "127.0.0.1");

        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        verify(googleAuthorizationStateStore).delete("state-123");
        verify(memberRepository).save(memberCaptor.capture());

        Member savedMember = memberCaptor.getValue();
        assertThat(savedMember.getGoogleSubject()).isEqualTo("google-sub");
        assertThat(savedMember.getEmail()).isEqualTo("member@example.com");
        assertThat(savedMember.getNickname()).isEqualTo("member-nick");
        assertThat(savedMember.isActive()).isTrue();
        assertThat(savedMember.getGameMoney()).isZero();
        assertThat(savedMember.getProfileImageKey()).isNull();
        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
    }

    /** AUTH-AC-15 */
    @Test
    void completeGoogleAuthorizationSavesNewMemberWithMemberRole() {
        GoogleAuthorizationCallbackRequest request = new GoogleAuthorizationCallbackRequest("auth-code", "state-123");
        GoogleAuthorizationContext context = new GoogleAuthorizationContext(
                GoogleAuthorizationAction.SIGNUP,
                "code-verifier",
                "ios",
                "member-nick",
                true,
                true
        );
        when(googleAuthorizationStateStore.find("state-123")).thenReturn(Optional.of(context));
        when(googleOauthClient.exchangeAuthorizationCode("auth-code", "code-verifier"))
                .thenReturn(tokenExchangeResponse());
        when(googleIdentityVerifier.verify("id-token"))
                .thenReturn(new GoogleIdentity("google-sub", "member@example.com", "Member Name"));
        when(memberRepository.existsByGoogleSubject("google-sub")).thenReturn(false);
        when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> {
            Member member = invocation.getArgument(0);
            ReflectionTestUtils.setField(member, "id", 11L);
            return member;
        });
        when(tokenService.issue(11L, "ROLE_MEMBER", "ios", "127.0.0.1"))
                .thenReturn(new TokenResponse("access", 900, "refresh", 2_592_000, 11L, "ROLE_MEMBER"));

        authService.completeGoogleAuthorization(request, "127.0.0.1");

        ArgumentCaptor<Member> memberCaptor = ArgumentCaptor.forClass(Member.class);
        verify(memberRepository).save(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getRole()).isEqualTo(MemberRole.MEMBER);
    }

    /** AUTH-AC-16 */
    @Test
    void completeGoogleAuthorizationIssuesTokenWithRoleDerivedFromMember() {
        GoogleAuthorizationCallbackRequest request = new GoogleAuthorizationCallbackRequest("auth-code", "state-123");
        GoogleAuthorizationContext context = new GoogleAuthorizationContext(
                GoogleAuthorizationAction.LOGIN,
                "code-verifier",
                "web",
                null,
                false,
                false
        );
        Member admin = Member.create("admin-sub", "admin@example.com", "admin-nick", true, true);
        ReflectionTestUtils.setField(admin, "id", 31L);
        ReflectionTestUtils.setField(admin, "role", MemberRole.ADMIN);

        when(googleAuthorizationStateStore.find("state-123")).thenReturn(Optional.of(context));
        when(googleOauthClient.exchangeAuthorizationCode("auth-code", "code-verifier"))
                .thenReturn(tokenExchangeResponse());
        when(googleIdentityVerifier.verify("id-token"))
                .thenReturn(new GoogleIdentity("admin-sub", "admin@example.com", "Admin Name"));
        when(memberRepository.findByGoogleSubject("admin-sub")).thenReturn(Optional.of(admin));
        when(tokenService.issue(31L, "ROLE_ADMIN", "web", "127.0.0.1"))
                .thenReturn(new TokenResponse("access", 900, "refresh", 2_592_000, 31L, "ROLE_ADMIN"));

        TokenResponse response = authService.completeGoogleAuthorization(request, "127.0.0.1");

        verify(tokenService).issue(31L, "ROLE_ADMIN", "web", "127.0.0.1");
        assertThat(response.role()).isEqualTo("ROLE_ADMIN");
    }

    /** AUTH-AC-09 */
    @Test
    void completeGoogleAuthorizationLogsInByGoogleSubject() {
        GoogleAuthorizationCallbackRequest request = new GoogleAuthorizationCallbackRequest("auth-code", "state-123");
        GoogleAuthorizationContext context = new GoogleAuthorizationContext(
                GoogleAuthorizationAction.LOGIN,
                "code-verifier",
                "web",
                null,
                false,
                false
        );
        Member member = Member.create("member-sub", "member@example.com", "nick", true, true);
        ReflectionTestUtils.setField(member, "id", 21L);

        when(googleAuthorizationStateStore.find("state-123")).thenReturn(Optional.of(context));
        when(googleOauthClient.exchangeAuthorizationCode("auth-code", "code-verifier"))
                .thenReturn(tokenExchangeResponse());
        when(googleIdentityVerifier.verify("id-token"))
                .thenReturn(new GoogleIdentity("member-sub", "member@example.com", "Member Name"));
        when(memberRepository.findByGoogleSubject("member-sub")).thenReturn(Optional.of(member));
        when(tokenService.issue(21L, "ROLE_MEMBER", "web", "127.0.0.1"))
                .thenReturn(new TokenResponse("access", 900, "refresh", 2_592_000, 21L, "ROLE_MEMBER"));

        TokenResponse response = authService.completeGoogleAuthorization(request, "127.0.0.1");

        assertThat(response.role()).isEqualTo("ROLE_MEMBER");
        assertThat(response.memberId()).isEqualTo(21L);
    }

    /** AUTH-AC-09 */
    @Test
    void completeGoogleAuthorizationFailsWhenMemberIsNotRegistered() {
        GoogleAuthorizationCallbackRequest request = new GoogleAuthorizationCallbackRequest("auth-code", "state-123");
        GoogleAuthorizationContext context = new GoogleAuthorizationContext(
                GoogleAuthorizationAction.LOGIN,
                "code-verifier",
                "web",
                null,
                false,
                false
        );
        when(googleAuthorizationStateStore.find("state-123")).thenReturn(Optional.of(context));
        when(googleOauthClient.exchangeAuthorizationCode("auth-code", "code-verifier"))
                .thenReturn(tokenExchangeResponse());
        when(googleIdentityVerifier.verify("id-token"))
                .thenReturn(new GoogleIdentity("unknown-sub", "unknown@example.com", "Unknown"));
        when(memberRepository.findByGoogleSubject("unknown-sub")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.completeGoogleAuthorization(request, "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND")
                .hasMessageContaining("Member is not registered");

        verify(googleAuthorizationStateStore).delete("state-123");
    }

    /** AUTH-AC-07 */
    @Test
    void completeGoogleAuthorizationRejectsUnknownState() {
        GoogleAuthorizationCallbackRequest request = new GoogleAuthorizationCallbackRequest("auth-code", "missing");
        when(googleAuthorizationStateStore.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.completeGoogleAuthorization(request, "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
    }
}
