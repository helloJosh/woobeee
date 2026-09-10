package com.woobeee.mvc.auth.service;

import com.woobeee.mvc.auth.api.request.GoogleAuthorizationCallbackRequest;
import com.woobeee.mvc.auth.api.request.LoginRequest;
import com.woobeee.mvc.auth.api.request.MemberSignupRequest;
import com.woobeee.mvc.auth.api.response.GoogleAuthorizationResponse;
import com.woobeee.mvc.auth.api.response.TokenResponse;
import com.woobeee.mvc.auth.config.GoogleOauthProperties;
import com.woobeee.mvc.auth.entity.Member;
import com.woobeee.mvc.auth.repository.MemberRepository;
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
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MemberRepository memberRepository;
    private final GoogleIdentityVerifier googleIdentityVerifier;
    private final GoogleOauthClient googleOauthClient;
    private final GoogleAuthorizationStateStore googleAuthorizationStateStore;
    private final GoogleOauthProperties googleOauthProperties;
    private final TokenGenerator tokenGenerator;
    private final TokenService tokenService;

    public GoogleAuthorizationResponse signup(MemberSignupRequest request) {
        return createAuthorizationResponse(new GoogleAuthorizationContext(
                GoogleAuthorizationAction.SIGNUP,
                nextCodeVerifier(),
                request.device(),
                request.nickname().trim(),
                request.termsAgreed(),
                request.privacyPolicyAgreed()
        ));
    }

    public GoogleAuthorizationResponse login(LoginRequest request) {
        return createAuthorizationResponse(new GoogleAuthorizationContext(
                GoogleAuthorizationAction.LOGIN,
                nextCodeVerifier(),
                request.device(),
                null,
                false,
                false
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
            case SIGNUP -> signup(identity, context, ip);
            case LOGIN -> login(identity, context, ip);
        };
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

    private TokenResponse signup(GoogleIdentity identity, GoogleAuthorizationContext context, String ip) {
        if (memberRepository.existsByGoogleSubject(identity.subject())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Member already exists");
        }

        Member member = memberRepository.save(Member.create(
                identity.subject(),
                identity.email(),
                context.nickname(),
                context.termsAgreed(),
                context.privacyPolicyAgreed()
        ));

        return tokenService.issue(member.getId(), member.getRole().name(), context.device(), ip);
    }

    /**
     * AUTH-AC-09/21 — 등록된 회원은 로그인, 미등록이면 그 자리에서 회원을 만들어 로그인시킨다(회원가입 화면을
     * 따로 거치지 않는다). 비활성 회원은 새로 만들지 않고 403 — 같은 google subject 로 두 번째 행을 만들면 안 된다.
     */
    private TokenResponse login(GoogleIdentity identity, GoogleAuthorizationContext context, String ip) {
        Member member = memberRepository.findByGoogleSubject(identity.subject())
                .orElseGet(() -> memberRepository.save(Member.create(
                        identity.subject(),
                        identity.email(),
                        nicknameFor(identity),
                        true,
                        true
                )));
        if (!member.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Member is deactivated");
        }
        return tokenService.issue(member.getId(), member.getRole().name(), context.device(), ip);
    }

    static final int MAX_NICKNAME_LENGTH = 60;
    static final String DEFAULT_NICKNAME = "Google 사용자";

    /** AUTH-AC-21 — Google 이름 → 이메일 @ 앞 → 기본값, 60자로 자른다(MemberSignupRequest 의 상한과 같다). */
    static String nicknameFor(GoogleIdentity identity) {
        String candidate = identity.name() == null ? "" : identity.name().trim();
        if (candidate.isEmpty() && identity.email() != null) {
            int at = identity.email().indexOf('@');
            candidate = (at > 0 ? identity.email().substring(0, at) : identity.email()).trim();
        }
        if (candidate.isEmpty()) {
            candidate = DEFAULT_NICKNAME;
        }
        return candidate.length() > MAX_NICKNAME_LENGTH ? candidate.substring(0, MAX_NICKNAME_LENGTH) : candidate;
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
}
