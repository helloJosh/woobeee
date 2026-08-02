package com.woobeee.game.api.error;

import org.springframework.web.server.ResponseStatusException;

/**
 * 코드를 달고 다니는 {@link ResponseStatusException}.
 *
 * <p>{@code ResponseStatusException} 을 계속 상속하는 것은, 이 예외가 컨트롤러 밖(예: WebSocket
 * 명령 처리)에서 던져지더라도 기존 동작이 그대로 유지되게 하기 위해서다. HTTP 경로에서는
 * {@link GameExceptionHandler} 가 코드를 꺼내 봉투에 싣는다.
 */
public class GameException extends ResponseStatusException {
    private final transient GameErrorCode errorCode;

    public GameException(GameErrorCode errorCode) {
        super(errorCode.status(), errorCode.reason());
        this.errorCode = errorCode;
    }

    public GameErrorCode errorCode() {
        return errorCode;
    }
}
