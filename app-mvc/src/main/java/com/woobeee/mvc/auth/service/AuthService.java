package com.woobeee.mvc.auth.service;

import com.woobeee.mvc.auth.api.request.BuyerSignupRequest;
import com.woobeee.mvc.auth.api.request.GoogleAuthorizationCallbackRequest;
import com.woobeee.mvc.auth.api.request.LoginRequest;
import com.woobeee.mvc.auth.api.request.SellerSignupRequest;
import com.woobeee.mvc.auth.api.response.GoogleAuthorizationResponse;
import com.woobeee.mvc.auth.api.response.TokenResponse;
import com.woobeee.mvc.auth.config.GoogleOauthProperties;
import com.woobeee.mvc.auth.entity.Buyer;
import com.woobeee.mvc.auth.entity.Seller;
import com.woobeee.mvc.auth.repository.BuyerRepository;
import com.woobeee.mvc.auth.repository.SellerRepository;
import com.woobeee.mvc.auth.service.dto.GoogleAuthorizationAction;
import com.woobeee.mvc.auth.service.dto.GoogleAuthorizationContext;
import com.woobeee.mvc.auth.service.dto.GoogleIdentity;
import com.woobeee.core.token.TokenGenerator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final BuyerRepository buyerRepository;
    private final SellerRepository sellerRepository;
    private final GoogleIdentityVerifier googleIdentityVerifier;
    private final GoogleOauthClient googleOauthClient;
    private final GoogleAuthorizationStateStore googleAuthorizationStateStore;
    private final GoogleOauthProperties googleOauthProperties;
    private final TokenGenerator tokenGenerator;
    private final TokenService tokenService;

    public GoogleAuthorizationResponse signupBuyer(BuyerSignupRequest request) {
        return createAuthorizationResponse(new GoogleAuthorizationContext(
                GoogleAuthorizationAction.BUYER_SIGNUP,
                nextCodeVerifier(),
                request.device(),
                null,
                request.nickname().trim(),
                request.termsAgreed(),
                request.privacyPolicyAgreed(),
                null
        ));
    }

    public GoogleAuthorizationResponse signupSeller(SellerSignupRequest request) {
        return createAuthorizationResponse(new GoogleAuthorizationContext(
                GoogleAuthorizationAction.SELLER_SIGNUP,
                nextCodeVerifier(),
                request.device(),
                null,
                request.nickname().trim(),
                request.termsAgreed(),
                request.privacyPolicyAgreed(),
                normalizeOptionalText(request.businessRegistrationCertificateUrl())
        ));
    }

    public GoogleAuthorizationResponse login(LoginRequest request) {
        return createAuthorizationResponse(new GoogleAuthorizationContext(
                GoogleAuthorizationAction.LOGIN,
                nextCodeVerifier(),
                request.device(),
                request.memberType(),
                null,
                false,
                false,
                null
        ));
    }

    @Transactional
    public TokenResponse completeGoogleAuthorization(GoogleAuthorizationCallbackRequest request, String ip) {
        GoogleAuthorizationContext context = googleAuthorizationStateStore.find(request.state())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid authorization state"));
        googleAuthorizationStateStore.delete(request.state());

        String idToken = googleOauthClient.exchangeAuthorizationCode(request.code(), context.codeVerifier()).idToken();
        GoogleIdentity identity = googleIdentityVerifier.verify(idToken);

        return switch (context.action()) {
            case BUYER_SIGNUP -> signupBuyer(identity, context, ip);
            case SELLER_SIGNUP -> signupSeller(identity, context, ip);
            case LOGIN -> login(identity, context, ip);
        };
    }

    private TokenResponse createSession(
            Long memberId,
            String role,
            String device,
            String ip
    ) {
        return tokenService.issue(memberId, role, device, ip);
    }

    private GoogleAuthorizationResponse createAuthorizationResponse(GoogleAuthorizationContext context) {
        String state = tokenGenerator.nextToken();
        googleAuthorizationStateStore.save(state, context);

        String authorizationUrl = UriComponentsBuilder
                .fromUriString(googleOauthProperties.getAuthorizationUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", googleOauthProperties.getClientId())
                .queryParam("redirect_uri", googleOauthProperties.getRedirectUri())
                .queryParam("scope", googleOauthProperties.getScope())
                .queryParam("state", state)
                .queryParam("code_challenge", toCodeChallenge(context.codeVerifier()))
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUriString();

        return new GoogleAuthorizationResponse(
                authorizationUrl,
                state,
                googleOauthProperties.getAuthorizationStateTtlSeconds()
        );
    }

    private TokenResponse signupBuyer(GoogleIdentity identity, GoogleAuthorizationContext context, String ip) {
        if (buyerRepository.existsByGoogleSubject(identity.subject())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Buyer already exists");
        }

        Buyer buyer = buyerRepository.save(Buyer.create(
                identity.subject(),
                identity.email(),
                context.nickname(),
                context.termsAgreed(),
                context.privacyPolicyAgreed()
        ));

        TokenResponse session = createSession(buyer.getId(), "ROLE_BUYER", context.device(), ip);
        return session;
    }

    private TokenResponse signupSeller(GoogleIdentity identity, GoogleAuthorizationContext context, String ip) {
        if (sellerRepository.existsByGoogleSubject(identity.subject())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Seller already exists");
        }

        Seller seller = sellerRepository.save(Seller.create(
                identity.subject(),
                identity.email(),
                context.nickname(),
                context.termsAgreed(),
                context.privacyPolicyAgreed(),
                context.businessRegistrationCertificateUrl()
        ));

        return createSession(seller.getId(), "ROLE_SELLER", context.device(), ip);
    }

    private TokenResponse login(GoogleIdentity identity, GoogleAuthorizationContext context, String ip) {
        if (context.memberType() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Member type is required");
        }

        return switch (context.memberType()) {
            case BUYER -> buyerRepository.findByGoogleSubject(identity.subject())
                    .filter(Buyer::isActive)
                    .map(buyer -> {
                        TokenResponse session =
                                createSession(buyer.getId(), context.memberType().roleName(), context.device(), ip);
                        return session;
                    })
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Buyer is not registered"));
            case SELLER -> sellerRepository.findByGoogleSubject(identity.subject())
                    .filter(Seller::isActive)
                    .map(seller -> createSession(seller.getId(), context.memberType().roleName(), context.device(), ip))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Seller is not registered"));
        };
    }

    private String nextCodeVerifier() {
        byte[] bytes = new byte[64];
        SECURE_RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    private String toCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 algorithm is not available", exception);
        }
    }

    private String normalizeOptionalText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
