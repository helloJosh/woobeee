package com.woobeee.mvc.auth.service;

import java.util.Map;
import java.util.Optional;

import com.woobeee.mvc.auth.config.GoogleOauthProperties;
import com.woobeee.mvc.auth.entity.MemberType;
import com.woobeee.mvc.auth.service.dto.GoogleAuthorizationAction;
import com.woobeee.mvc.auth.service.dto.GoogleAuthorizationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class RedisGoogleAuthorizationStateStore implements GoogleAuthorizationStateStore {
    private final StringRedisTemplate redisTemplate;
    private final GoogleOauthProperties googleOauthProperties;

    @Override
    public void save(String state, GoogleAuthorizationContext context) {
        String key = key(state);
        redisTemplate.opsForHash().putAll(key, Map.of(
                "action", context.action().name(),
                "codeVerifier", context.codeVerifier(),
                "device", context.device(),
                "memberType", context.memberType() == null ? "" : context.memberType().name(),
                "nickname", context.nickname() == null ? "" : context.nickname(),
                "termsAgreed", String.valueOf(context.termsAgreed()),
                "privacyPolicyAgreed", String.valueOf(context.privacyPolicyAgreed()),
                "businessRegistrationCertificateUrl", context.businessRegistrationCertificateUrl() == null
                        ? ""
                        : context.businessRegistrationCertificateUrl()
        ));
        redisTemplate.expire(key, java.time.Duration.ofSeconds(googleOauthProperties.getAuthorizationStateTtlSeconds()));
    }

    @Override
    public Optional<GoogleAuthorizationContext> find(String state) {
        Map<Object, Object> values = redisTemplate.opsForHash().entries(key(state));
        if (values.isEmpty()) {
            return Optional.empty();
        }

        String action = readString(values, "action");
        String codeVerifier = readString(values, "codeVerifier");
        String device = readString(values, "device");
        if (action == null || codeVerifier == null || device == null) {
            return Optional.empty();
        }

        String memberTypeValue = readString(values, "memberType");
        return Optional.of(new GoogleAuthorizationContext(
                GoogleAuthorizationAction.valueOf(action),
                codeVerifier,
                device,
                memberTypeValue == null || memberTypeValue.isBlank() ? null : MemberType.valueOf(memberTypeValue),
                readString(values, "nickname"),
                Boolean.parseBoolean(readString(values, "termsAgreed")),
                Boolean.parseBoolean(readString(values, "privacyPolicyAgreed")),
                normalizeBlank(readString(values, "businessRegistrationCertificateUrl"))
        ));
    }

    @Override
    public void delete(String state) {
        redisTemplate.delete(key(state));
    }

    private String key(String state) {
        return "auth:oauth:google:state:" + state;
    }

    private String readString(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private String normalizeBlank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value;
    }
}
