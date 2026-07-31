package com.woobeee.game.api;

import com.woobeee.core.api.ApiResponse;
import com.woobeee.game.security.GameAuthWebFilter;
import com.woobeee.game.security.GamePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
        Object principal = exchange.getAttribute(GameAuthWebFilter.PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof GamePrincipal gamePrincipal)) {
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access token is required"));
        }

        return Mono.just(ApiResponse.success(gamePrincipal, "Principal resolved"));
    }
}
