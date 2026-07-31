package com.woobeee.game.security;

import java.util.Map;

import com.woobeee.core.token.dto.AuthTokenType;
import com.woobeee.core.token.dto.TokenMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class ReactiveTokenVerifier {
    private final ReactiveStringRedisTemplate redisTemplate;

    public Mono<TokenMetadata> verify(String accessToken) {
        String key = AuthTokenType.ACCESS.redisKey(accessToken);

        return redisTemplate.<String, String>opsForHash()
                .entries(key)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .flatMap(this::toMetadata);
    }

    private Mono<TokenMetadata> toMetadata(Map<String, String> values) {
        String memberId = values.get("memberId");
        String role = values.get("role");
        String device = values.get("device");
        String ip = values.get("ip");

        if (memberId == null || role == null || device == null || ip == null) {
            return Mono.empty();
        }

        return Mono.just(new TokenMetadata(Long.valueOf(memberId), role, device, ip));
    }
}
