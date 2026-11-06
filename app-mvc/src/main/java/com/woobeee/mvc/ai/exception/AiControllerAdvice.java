package com.woobeee.mvc.ai.exception;

import com.woobeee.core.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.time.LocalDateTime;

/** PokemonControllerAdvice 와 같은 모양 — 실패 응답을 ApiResponse 봉투 + 코드 키로 맞춘다. */
@RestControllerAdvice(basePackages = "com.woobeee.mvc.ai")
@Slf4j
public class AiControllerAdvice {

    @ExceptionHandler(AiException.class)
    public ResponseEntity<ApiResponse<LocalDateTime>> handleAiException(AiException ex) {
        log.debug("ai api rejected a request: {}", ex.getMessage());
        return envelope(ex.errorCode());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<LocalDateTime>> handleTooLarge(MaxUploadSizeExceededException ex) {
        return envelope(AiErrorCode.IMAGE_TOO_LARGE);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, HttpRequestMethodNotSupportedException.class})
    public ResponseEntity<ApiResponse<LocalDateTime>> handleBadRequest(Exception ex) {
        log.debug("ai api rejected a malformed request: {}", ex.getMessage());
        return envelope(AiErrorCode.BAD_REQUEST);
    }

    /** 그 밖의 모든 것. 예외 메시지는 절대 본문에 싣지 않는다 — 진단은 로그에서 한다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<LocalDateTime>> handleUnexpected(Exception ex) {
        log.error("ai api failed unexpectedly", ex);
        return envelope(AiErrorCode.UNEXPECTED);
    }

    private ResponseEntity<ApiResponse<LocalDateTime>> envelope(AiErrorCode errorCode) {
        return ResponseEntity.status(errorCode.status()).body(ApiResponse.fail(errorCode.status(), errorCode.code()));
    }
}
