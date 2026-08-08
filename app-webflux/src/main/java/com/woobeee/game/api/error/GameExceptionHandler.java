package com.woobeee.game.api.error;

import com.woobeee.core.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * 게임 HTTP API 의 실패 응답을 app-mvc 와 같은 {@link ApiResponse} 봉투로 맞춘다.
 *
 * <p>이게 없으면 WebFlux 기본 오류 본문({@code timestamp/path/status/error/message})이 나가고,
 * {@code front/lib/api.ts} 는 거기서 {@code header} 를 찾지 못해 코드를 {@code "unknown"} 으로
 * 두고 만다 — 초대 코드 오류든 닉네임 중복이든 화면에는 "예기치 못한 오류가 발생했습니다."
 * 한 줄만 뜨게 된다.
 *
 * <p>범위를 {@code com.woobeee.game.api} 로 못박은 이유:
 * <ul>
 *   <li>WebSocket 핸드셰이크(`/ws/game`)는 {@code @Controller} 가 아니라 핸들러 매핑이 처리하므로
 *       {@code @ControllerAdvice} 의 사정권 밖이다. 여기서 가로채 HTTP 봉투를 씌우면 업그레이드가
 *       깨진다 — 그럴 일이 없도록 어드바이스로 두고, 전역
 *       {@code ErrorWebExceptionHandler} 로는 만들지 않았다.</li>
 *   <li>{@code GameAuthWebFilter} 는 예외를 던지지 않는다(토큰이 무효하면 principal 을 심지
 *       않고 체인을 이어 간다). 401 은 컨트롤러 안의 {@code GamePrincipals.require} 가 던지므로
 *       이 어드바이스가 정상적으로 받는다.</li>
 * </ul>
 *
 * <p>모든 메서드는 {@code Mono} 를 돌려주고 블로킹 호출을 하지 않는다.
 */
@RestControllerAdvice(basePackages = "com.woobeee.game.api")
@Slf4j
public class GameExceptionHandler {

    /** 도메인이 코드를 달아 던진 예외. 그 코드가 그대로 나간다. */
    @ExceptionHandler(GameException.class)
    public Mono<ResponseEntity<ApiResponse<LocalDateTime>>> handleGameException(GameException ex) {
        log.debug("game api rejected a request: {}", ex.getReason());
        return envelope(ex.errorCode());
    }

    /**
     * 코드가 없는 {@code ResponseStatusException} — 대부분 프레임워크가 만든 것이다
     * (bean validation 실패의 {@code WebExchangeBindException}, 잘못된 JSON, 405, 415 …).
     * 상태는 살리고 코드는 폴백으로 채운다.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<ApiResponse<LocalDateTime>>> handleResponseStatus(ResponseStatusException ex) {
        log.debug("game api rejected a request: {} {}", ex.getStatusCode(), ex.getReason());
        return envelope(GameErrorCode.fromStatus(ex.getStatusCode()));
    }

    /**
     * 그 밖의 모든 것. 예외 메시지는 <b>절대</b> 본문에 싣지 않는다 — 접속 문자열이나 자격증명이
     * 들어 있는 일이 흔하다. 진단은 로그에서 한다.
     */
    @ExceptionHandler(Throwable.class)
    public Mono<ResponseEntity<ApiResponse<LocalDateTime>>> handleUnexpected(Throwable ex) {
        log.error("game api failed unexpectedly", ex);
        return envelope(GameErrorCode.UNEXPECTED);
    }

    private Mono<ResponseEntity<ApiResponse<LocalDateTime>>> envelope(GameErrorCode errorCode) {
        return Mono.just(ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.fail(errorCode.status(), errorCode.code())));
    }
}
