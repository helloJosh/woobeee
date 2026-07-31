package com.woobeee.mvc.auth.service;

import com.woobeee.mvc.auth.api.response.TokenResponse;
import com.woobeee.core.token.TokenGenerator;
import com.woobeee.core.token.TokenStore;
import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.core.token.dto.TokenSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class TokenService {
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

        tokenStore.save(accessToken, AuthTokenType.ACCESS, metadata);
        tokenStore.save(refreshToken, AuthTokenType.REFRESH, metadata);

        return new TokenResponse(
                accessToken,
                AuthTokenType.ACCESS.ttl().toSeconds(),
                refreshToken,
                AuthTokenType.REFRESH.ttl().toSeconds(),
                metadata.memberId(),
                metadata.role()
        );
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
