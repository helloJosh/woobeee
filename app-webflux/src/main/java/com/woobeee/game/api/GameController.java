package com.woobeee.game.api;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.game.security.GamePrincipal;
import com.woobeee.game.security.GamePrincipals;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/game")
public class GameController {

    @GetMapping("/health")
    public Mono<ApiResponse<String>> health() {
        return Mono.just(ApiResponse.success("UP", "Game surface is up"));
    }

    @GetMapping("/me")
    public Mono<ApiResponse<GamePrincipal>> me(ServerWebExchange exchange) {
        GamePrincipal principal = GamePrincipals.require(exchange);
        return Mono.just(ApiResponse.success(principal, "Principal resolved"));
    }
}
