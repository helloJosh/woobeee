package com.woobeee.mvc.schedule.exception;

import com.woobeee.core.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

/**
 * schedule API 의 실패 응답을 ApiResponse 봉투 + 코드 키로 맞춘다.
 * app-mvc 의 알려진 결함(header.message 에 영어 문장 → 프론트 코드 키와 불일치)을
 * 새 도메인에서 반복하지 않기 위한 것으로, app-webflux 의 GameExceptionHandler 를 옮긴 모양이다.
 */
@RestControllerAdvice(basePackages = "com.woobeee.mvc.schedule")
@Slf4j
public class ScheduleControllerAdvice {

    @ExceptionHandler(ScheduleException.class)
    public ResponseEntity<ApiResponse<LocalDateTime>> handleScheduleException(ScheduleException ex) {
        log.debug("schedule api rejected a request: {}", ex.getMessage());
        return envelope(ex.errorCode());
    }

    /** bean validation 실패와 깨진 JSON — 상태는 400, 코드는 폴백. */
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<LocalDateTime>> handleBadRequest(Exception ex) {
        log.debug("schedule api rejected a malformed request: {}", ex.getMessage());
        return envelope(ScheduleErrorCode.BAD_REQUEST);
    }

    /** 그 밖의 모든 것. 예외 메시지는 절대 본문에 싣지 않는다 — 진단은 로그에서 한다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<LocalDateTime>> handleUnexpected(Exception ex) {
        log.error("schedule api failed unexpectedly", ex);
        return envelope(ScheduleErrorCode.UNEXPECTED);
    }

    private ResponseEntity<ApiResponse<LocalDateTime>> envelope(ScheduleErrorCode errorCode) {
        return ResponseEntity
                .status(errorCode.status())
                .body(ApiResponse.fail(errorCode.status(), errorCode.code()));
    }
}
