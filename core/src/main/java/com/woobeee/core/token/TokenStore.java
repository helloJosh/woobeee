package com.woobeee.core.token;

import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.core.token.dto.TokenSnapshot;
import java.util.Optional;

public interface TokenStore {
    void save(String token, AuthTokenType tokenType, TokenMetadata metadata);

    Optional<TokenSnapshot> find(String token, AuthTokenType tokenType);

    void delete(String token, AuthTokenType tokenType);
}
