package com.woobeee.mvc.auth.exception;

import com.woobeee.core.api.ApiResponse;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.woobeee.mvc.auth")
@Slf4j
public class AuthRestControllerAdvice {

    /**
     * BAD_REQUEST(400) 처리 메소드
     *
     * @param ex Exception
     * @return ApiResponse<Void>
     */
    @ExceptionHandler({
            MethodArgumentNotValidException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<LocalDateTime> badRequestExceptionHandler(MethodArgumentNotValidException ex) {
        log.error(ex.getMessage(), ex);

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Invalid request");

        return ApiResponse.fail(
                HttpStatus.BAD_REQUEST,
                message
        );
    }
}
