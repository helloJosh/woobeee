package com.woobeee.core.token;

import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.core.token.dto.TokenSnapshot;
import java.util.Optional;

public interface TokenStore {
    /** 타입 기본 TTL 로 저장한다. */
    default void save(String token, AuthTokenType tokenType, TokenMetadata metadata) {
        save(token, tokenType, metadata, tokenType.ttl());
    }

    /** 발급 정책이 TTL 을 정하는 경우(예: ADMIN access 1일). 검증 쪽은 Redis 의 남은 TTL 만 본다. */
    void save(String token, AuthTokenType tokenType, TokenMetadata metadata, java.time.Duration ttl);

    Optional<TokenSnapshot> find(String token, AuthTokenType tokenType);

    void delete(String token, AuthTokenType tokenType);
}
