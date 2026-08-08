package com.woobeee.game.security;

import com.woobeee.game.api.error.GameErrorCode;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

/**
 * {@link GameAuthWebFilter} 가 exchange 속성에 심어 둔 {@link GamePrincipal} 을 꺼내는
 * 공용 헬퍼. 컨트롤러마다 따로 두면(이전에 {@code RoomController} 와 {@code GameController} 가
 * 그랬듯) 구현이 갈라지기 쉽다 — 여기 하나로 모은다.
 */
public final class GamePrincipals {
    private GamePrincipals() {
    }

    /** @throws ResponseStatusException 401 Unauthorized, access token 이 없거나 무효하면. */
    public static GamePrincipal require(ServerWebExchange exchange) {
        Object principal = exchange.getAttribute(GameAuthWebFilter.PRINCIPAL_ATTRIBUTE);
        if (!(principal instanceof GamePrincipal gamePrincipal)) {
            throw GameErrorCode.UNAUTHORIZED.asException();
        }
        return gamePrincipal;
    }
}
