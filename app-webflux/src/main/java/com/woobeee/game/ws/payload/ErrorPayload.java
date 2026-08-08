package com.woobeee.game.ws.payload;

import com.woobeee.game.api.error.GameErrorCode;

/**
 * WebSocket {@code ERROR} 메시지의 페이로드.
 *
 * <p>HTTP 실패 응답과 같은 역할 분담을 쓴다: {@code status} 는 숫자 상태, {@code code} 는
 * 프론트가 문구를 찾는 키({@code game_*}), {@code message} 는 사람이 읽는 영어 설명이다.
 * 두 통로에서 "code" 라는 낱말이 서로 다른 것을 가리키면(한쪽은 숫자, 한쪽은 문자열) 반드시
 * 사고가 난다 — 그래서 숫자는 {@code status} 로 부른다.
 *
 * <p>{@code message} 에는 예외 메시지를 그대로 담지 않는다. 카탈로그에 적힌 문구만 나간다.
 */
public record ErrorPayload(
        int status,
        String code,
        String message
) {
    public static ErrorPayload of(GameErrorCode errorCode) {
        return new ErrorPayload(errorCode.status().value(), errorCode.code(), errorCode.reason());
    }
}
