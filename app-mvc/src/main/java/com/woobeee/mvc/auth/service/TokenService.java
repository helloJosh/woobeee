package com.woobeee.mvc.auth.service;

import com.woobeee.mvc.auth.api.response.TokenResponse;
import com.woobeee.mvc.auth.entity.MemberRole;
import com.woobeee.core.token.TokenGenerator;
import com.woobeee.core.token.TokenStore;
import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.core.token.dto.TokenSnapshot;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class TokenService {
    // 긴 글 작성 중 access 만료를 겪지 않도록 ADMIN 만 길게 발급한다(AUTH-AC-17, 사용자 결정).
    // refresh 회전과 401→refresh 재시도는 그대로이므로 보안 완화 폭은 access 수명뿐이다.
    private static final Duration ADMIN_ACCESS_TTL = Duration.ofDays(1);

    private final TokenStore tokenStore;
    private final TokenGenerator tokenGenerator;

    public TokenResponse issue(Long memberId, String role, String device, String ip) {
        return createAndStoreTokens(new TokenMetadata(memberId, role, device, ip));
    }

    public TokenResponse refresh(String refreshToken, String device, String ip) {
        TokenSnapshot snapshot = tokenStore.find(refreshToken, AuthTokenType.REFRESH)
                .filter(stored -> stored.ttlSeconds() > 0)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        TokenMetadata metadata = snapshot.metadata();
        validateRefreshMetadata(metadata, device, ip, snapshot.ttlSeconds());

        TokenResponse response = createAndStoreTokens(metadata);
        tokenStore.delete(refreshToken, AuthTokenType.REFRESH);
        return response;
    }

    private TokenResponse createAndStoreTokens(TokenMetadata metadata) {
        String accessToken = tokenGenerator.nextToken();
        String refreshToken = tokenGenerator.nextToken();

        Duration accessTtl = accessTtlFor(metadata.role());
        tokenStore.save(accessToken, AuthTokenType.ACCESS, metadata, accessTtl);
        tokenStore.save(refreshToken, AuthTokenType.REFRESH, metadata, AuthTokenType.REFRESH.ttl());

        return new TokenResponse(
                accessToken,
                accessTtl.toSeconds(),
                refreshToken,
                AuthTokenType.REFRESH.ttl().toSeconds(),
                metadata.memberId(),
                metadata.role()
        );
    }

    private Duration accessTtlFor(String role) {
        return MemberRole.ROLE_ADMIN.name().equals(role)
                ? ADMIN_ACCESS_TTL
                : AuthTokenType.ACCESS.ttl();
    }

    private void validateRefreshMetadata(TokenMetadata metadata, String device, String ip, long ttlSeconds) {
        if (!StringUtils.hasText(metadata.role())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Stored role is invalid");
        }
        if (!StringUtils.hasText(metadata.device()) || !metadata.device().equals(device)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Device does not match");
        }
        if (ttlSeconds <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }

        if (!StringUtils.hasText(metadata.ip()) || !metadata.ip().equals(ip)) {
            //TODO : IP가 다를시 경고 알림 기능 추가
        }
    }
}
