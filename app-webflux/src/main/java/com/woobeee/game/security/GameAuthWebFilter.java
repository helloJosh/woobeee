package com.woobeee.game.security;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class GameAuthWebFilter implements WebFilter {
    public static final String PRINCIPAL_ATTRIBUTE = "woobeee.game.principal";

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final ReactiveTokenVerifier reactiveTokenVerifier;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String accessToken = resolveAccessToken(exchange);
        if (!StringUtils.hasText(accessToken)) {
            return chain.filter(exchange);
        }

        // then() 은 검증이 비어서 완료돼도(= 토큰 무효) 체인을 한 번 실행한다.
        // switchIfEmpty 를 덧붙이면 Mono<Void> 는 값을 절대 emit 하지 않으므로
        // 항상 발동해 체인이 두 번 구독된다 — 그래서 쓰지 않는다.
        return reactiveTokenVerifier.verify(accessToken)
                .doOnNext(metadata -> exchange.getAttributes().put(
                        PRINCIPAL_ATTRIBUTE,
                        new GamePrincipal(metadata.memberId(), metadata.role(), metadata.device())
                ))
                .then(Mono.defer(() -> chain.filter(exchange)));
    }

    private String resolveAccessToken(ServerWebExchange exchange) {
        String authorization = exchange.getRequest().getHeaders().getFirst(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(authorization) || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        return StringUtils.hasText(token) ? token : null;
    }
}
