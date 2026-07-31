package com.woobeee.core.token.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AuthTokenTypeTest {

    @Test
    void accessTokenKeyContractIsStableAcrossApps() {
        assertThat(AuthTokenType.ACCESS.redisKey("tok-1"))
                .isEqualTo("auth:token:access:tok-1");
        assertThat(AuthTokenType.ACCESS.reverseKey(7L, "ios"))
                .isEqualTo("auth:user-token:access:7:ios");
        assertThat(AuthTokenType.ACCESS.ttl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void refreshTokenKeyContractIsStableAcrossApps() {
        assertThat(AuthTokenType.REFRESH.redisKey("tok-2"))
                .isEqualTo("auth:token:refresh:tok-2");
        assertThat(AuthTokenType.REFRESH.reverseKey(9L, "web"))
                .isEqualTo("auth:user-token:refresh:9:web");
        assertThat(AuthTokenType.REFRESH.ttl()).isEqualTo(Duration.ofDays(30));
    }
}
