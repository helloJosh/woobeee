package com.woobeee.mvc.auth.service;

import com.woobeee.mvc.auth.api.request.BuyerSignupRequest;
import com.woobeee.mvc.auth.api.request.GoogleAuthorizationCallbackRequest;
import com.woobeee.mvc.auth.api.request.LoginRequest;
import com.woobeee.mvc.auth.api.response.GoogleAuthorizationResponse;
import com.woobeee.mvc.auth.api.response.TokenResponse;
import com.woobeee.mvc.auth.config.GoogleOauthProperties;
import com.woobeee.mvc.auth.entity.Buyer;
import com.woobeee.mvc.auth.entity.MemberType;
import com.woobeee.mvc.auth.repository.BuyerRepository;
import com.woobeee.mvc.auth.repository.SellerRepository;
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
    private BuyerRepository buyerRepository;

    @Mock
    private SellerRepository sellerRepository;

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

    @Test
    void signupBuyerCreatesAuthorizationResponseAndStoresContext() {
        mockAuthorizationProperties();
        BuyerSignupRequest request = new BuyerSignupRequest("buyer-nick", true, true, "ios");
        when(tokenGenerator.nextToken()).thenReturn("state-123");

        GoogleAuthorizationResponse response = authService.signupBuyer(request);

        ArgumentCaptor<GoogleAuthorizationContext> contextCaptor =
                ArgumentCaptor.forClass(GoogleAuthorizationContext.class);
        verify(googleAuthorizationStateStore).save(eq("state-123"), contextCaptor.capture());

        GoogleAuthorizationContext context = contextCaptor.getValue();
        assertThat(context.action()).isEqualTo(GoogleAuthorizationAction.BUYER_SIGNUP);
        assertThat(context.device()).isEqualTo("ios");
        assertThat(context.nickname()).isEqualTo("buyer-nick");
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
        LoginRequest request = new LoginRequest(MemberType.BUYER, "android");
        when(tokenGenerator.nextToken()).thenReturn("login-state");

        GoogleAuthorizationResponse response = authService.login(request);

        ArgumentCaptor<GoogleAuthorizationContext> contextCaptor =
                ArgumentCaptor.forClass(GoogleAuthorizationContext.class);
        verify(googleAuthorizationStateStore).save(eq("login-state"), contextCaptor.capture());

        GoogleAuthorizationContext context = contextCaptor.getValue();
        assertThat(context.action()).isEqualTo(GoogleAuthorizationAction.LOGIN);
        assertThat(context.memberType()).isEqualTo(MemberType.BUYER);
        assertThat(context.device()).isEqualTo("android");
        assertThat(response.state()).isEqualTo("login-state");
    }

    @Test
    void completeGoogleAuthorizationSignsUpBuyerAndIssuesTokens() {
        GoogleAuthorizationCallbackRequest request = new GoogleAuthorizationCallbackRequest("auth-code", "state-123");
        GoogleAuthorizationContext context = new GoogleAuthorizationContext(
                GoogleAuthorizationAction.BUYER_SIGNUP,
                "code-verifier",
                "ios",
                null,
                "buyer-nick",
                true,
                true,
                null
        );
        when(googleAuthorizationStateStore.find("state-123")).thenReturn(Optional.of(context));
        when(googleOauthClient.exchangeAuthorizationCode("auth-code", "code-verifier"))
                .thenReturn(new GoogleTokenExchangeResponse(
                        "google-access",
                        3600L,
                        null,
                        "openid email profile",
                        "Bearer",
                        "id-token"
                ));
        when(googleIdentityVerifier.verify("id-token"))
                .thenReturn(new GoogleIdentity("google-sub", "buyer@example.com", "Buyer Name"));
        when(buyerRepository.existsByGoogleSubject("google-sub")).thenReturn(false);
        when(buyerRepository.save(any(Buyer.class))).thenAnswer(invocation -> {
            Buyer buyer = invocation.getArgument(0);
            ReflectionTestUtils.setField(buyer, "id", 11L);
            return buyer;
        });
        when(tokenService.issue(11L, "ROLE_BUYER", "ios", "127.0.0.1"))
                .thenReturn(new TokenResponse("access", 900, "refresh", 2_592_000, 11L, "ROLE_BUYER"));

        TokenResponse response = authService.completeGoogleAuthorization(request, "127.0.0.1");

        ArgumentCaptor<Buyer> buyerCaptor = ArgumentCaptor.forClass(Buyer.class);
        verify(googleAuthorizationStateStore).delete("state-123");
        verify(buyerRepository).save(buyerCaptor.capture());

        Buyer savedBuyer = buyerCaptor.getValue();
        assertThat(savedBuyer.getGoogleSubject()).isEqualTo("google-sub");
        assertThat(savedBuyer.getEmail()).isEqualTo("buyer@example.com");
        assertThat(savedBuyer.getNickname()).isEqualTo("buyer-nick");
        assertThat(response.accessToken()).isEqualTo("access");
        assertThat(response.refreshToken()).isEqualTo("refresh");
    }

    @Test
    void completeGoogleAuthorizationFailsWhenSellerIsNotRegistered() {
        GoogleAuthorizationCallbackRequest request = new GoogleAuthorizationCallbackRequest("auth-code", "state-123");
        GoogleAuthorizationContext context = new GoogleAuthorizationContext(
                GoogleAuthorizationAction.LOGIN,
                "code-verifier",
                "web",
                MemberType.SELLER,
                null,
                false,
                false,
                null
        );
        when(googleAuthorizationStateStore.find("state-123")).thenReturn(Optional.of(context));
        when(googleOauthClient.exchangeAuthorizationCode("auth-code", "code-verifier"))
                .thenReturn(new GoogleTokenExchangeResponse(
                        "google-access",
                        3600L,
                        null,
                        "openid email profile",
                        "Bearer",
                        "id-token"
                ));
        when(googleIdentityVerifier.verify("id-token"))
                .thenReturn(new GoogleIdentity("seller-sub", "seller@example.com", "Seller Name"));
        when(sellerRepository.findByGoogleSubject("seller-sub")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.completeGoogleAuthorization(request, "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404 NOT_FOUND")
                .hasMessageContaining("Seller is not registered");

        verify(googleAuthorizationStateStore).delete("state-123");
    }
}
