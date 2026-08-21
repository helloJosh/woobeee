package com.woobeee.mvc.auth.service;

import com.woobeee.mvc.auth.api.response.TokenResponse;
import com.woobeee.core.token.TokenGenerator;
import com.woobeee.core.token.TokenStore;
import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.core.token.dto.TokenSnapshot;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {
    @Mock
    private TokenStore tokenStore;

    @Mock
    private TokenGenerator tokenGenerator;

    @InjectMocks
    private TokenService tokenService;

    @Test
    void refreshIssuesNewAccessAndRefreshTokens() {
        TokenMetadata metadata = new TokenMetadata(7L, "ROLE_USER", "ios", "127.0.0.1");
        when(tokenStore.find("refresh-1", AuthTokenType.REFRESH))
                .thenReturn(Optional.of(new TokenSnapshot(metadata, 60L)));
        when(tokenGenerator.nextToken())
                .thenReturn("new-access", "new-refresh");

        TokenResponse response = tokenService.refresh("refresh-1", "ios", "127.0.0.1");

        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        assertThat(response.accessTokenExpiresInSeconds()).isEqualTo(900);
        assertThat(response.refreshTokenExpiresInSeconds()).isEqualTo(2_592_000);
        verify(tokenStore).save("new-access", AuthTokenType.ACCESS, metadata, java.time.Duration.ofMinutes(15));
        verify(tokenStore).save("new-refresh", AuthTokenType.REFRESH, metadata, java.time.Duration.ofDays(30));
        verify(tokenStore).delete("refresh-1", AuthTokenType.REFRESH);
    }

    @Test
    void refreshRejectsDeviceMismatch() {
        when(tokenStore.find("refresh-1", AuthTokenType.REFRESH))
                .thenReturn(Optional.of(new TokenSnapshot(
                        new TokenMetadata(7L, "ROLE_USER", "ios", "127.0.0.1"),
                        60L
                )));

        assertThatThrownBy(() -> tokenService.refresh("refresh-1", "android", "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED")
                .hasMessageContaining("Device does not match");

        verify(tokenStore, never()).save("new-access", AuthTokenType.ACCESS, new TokenMetadata(7L, "ROLE_USER", "ios", "127.0.0.1"));
    }

    @Test
    void refreshIssuesTokensWhenOnlyIpDiffers() {
        TokenMetadata metadata = new TokenMetadata(7L, "ROLE_USER", "ios", "127.0.0.1");
        when(tokenStore.find("refresh-1", AuthTokenType.REFRESH))
                .thenReturn(Optional.of(new TokenSnapshot(metadata, 60L)));
        when(tokenGenerator.nextToken())
                .thenReturn("new-access", "new-refresh");

        TokenResponse response = tokenService.refresh("refresh-1", "ios", "10.0.0.1");

        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        verify(tokenStore).save("new-access", AuthTokenType.ACCESS, metadata, java.time.Duration.ofMinutes(15));
        verify(tokenStore).save("new-refresh", AuthTokenType.REFRESH, metadata, java.time.Duration.ofDays(30));
        verify(tokenStore).delete("refresh-1", AuthTokenType.REFRESH);
    }

    /** AUTH-AC-17 — 긴 글 작성 중 만료 방지: ADMIN access 는 1일 */
    @Test
    void adminAccessTokenIsIssuedForOneDay() {
        when(tokenGenerator.nextToken()).thenReturn("admin-access", "admin-refresh");

        TokenResponse response = tokenService.issue(3L, "ROLE_ADMIN", "web", "127.0.0.1");

        assertThat(response.accessTokenExpiresInSeconds()).isEqualTo(86_400);
        verify(tokenStore).save(
                "admin-access",
                AuthTokenType.ACCESS,
                new TokenMetadata(3L, "ROLE_ADMIN", "web", "127.0.0.1"),
                java.time.Duration.ofDays(1));
    }

    /** AUTH-AC-17 — 그 외 role 은 기본 15분 유지 */
    @Test
    void memberAccessTokenKeepsDefaultTtl() {
        when(tokenGenerator.nextToken()).thenReturn("member-access", "member-refresh");

        TokenResponse response = tokenService.issue(7L, "ROLE_MEMBER", "web", "127.0.0.1");

        assertThat(response.accessTokenExpiresInSeconds()).isEqualTo(900);
        verify(tokenStore).save(
                "member-access",
                AuthTokenType.ACCESS,
                new TokenMetadata(7L, "ROLE_MEMBER", "web", "127.0.0.1"),
                java.time.Duration.ofMinutes(15));
    }

    @Test
    void refreshRejectsMissingRefreshToken() {
        when(tokenStore.find("missing", AuthTokenType.REFRESH))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> tokenService.refresh("missing", "ios", "127.0.0.1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED")
                .hasMessageContaining("Invalid refresh token");

        verify(tokenStore, never()).delete("missing", AuthTokenType.REFRESH);
    }
}
