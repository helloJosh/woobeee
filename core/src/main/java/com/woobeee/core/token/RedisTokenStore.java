package com.woobeee.core.token;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import com.woobeee.core.token.dto.TokenSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisTokenStore implements TokenStore {
    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(String token, AuthTokenType tokenType, TokenMetadata metadata, Duration ttl) {
        String key = tokenType.redisKey(token);
        String reverseKey = tokenType.reverseKey(metadata.memberId(), metadata.device());

        redisTemplate.opsForHash().putAll(
                key,
                Map.of(
                "memberId", String.valueOf(metadata.memberId()),
                "role", metadata.role(),
                "device", metadata.device(),
                "ip", metadata.ip())
        );
        redisTemplate.expire(key, ttl);
        redisTemplate.opsForValue().set(reverseKey, token, ttl);
    }

    @Override
    public Optional<TokenSnapshot> find(String token, AuthTokenType tokenType) {
        String key = tokenType.redisKey(token);
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key);
        if (values.isEmpty()) {
            return Optional.empty();
        }

        Long ttlSeconds = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttlSeconds == null || ttlSeconds <= 0) {
            return Optional.empty();
        }

        Object memberId = values.get("memberId");
        Object role = values.get("role");
        Object device = values.get("device");
        Object ip = values.get("ip");
        if (memberId == null || role == null || device == null || ip == null) {
            return Optional.empty();
        }

        return Optional.of(new TokenSnapshot(
                new TokenMetadata(
                        Long.valueOf(memberId.toString()),
                        role.toString(),
                        device.toString(),
                        ip.toString()
                ),
                ttlSeconds
        ));
    }

    @Override
    public void delete(String token, AuthTokenType tokenType) {
        Optional<TokenSnapshot> snapshot = find(token, tokenType);
        redisTemplate.delete(tokenType.redisKey(token));

        snapshot.ifPresent(value -> {
            String reverseKey = tokenType.reverseKey(value.metadata().memberId(), value.metadata().device());
            String currentToken = redisTemplate.opsForValue().get(reverseKey);
            if (token.equals(currentToken)) {
                redisTemplate.delete(reverseKey);
            }
        });
    }
}
