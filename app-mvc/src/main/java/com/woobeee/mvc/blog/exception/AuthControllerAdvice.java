package com.woobeee.mvc.blog.exception;

import com.woobeee.core.api.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;

@RestControllerAdvice
@Slf4j
public class AuthControllerAdvice {
//    /**
//     * INTERNAL_SERVER_ERROR(500) 처리 메소드
//     *
//     * @param ex Exception
//     * @return ApiResponse<Void>
//     */
//    @ExceptionHandler({
//    })
//    @ResponseStatus(HttpStatus.BAD_REQUEST)
//    public ApiResponse<LocalDateTime> badRequestExceptionHandler(Exception ex) {
//        log.error(ex.getMessage(), ex);
//
//        return ApiResponse.fail(
//                HttpStatus.BAD_REQUEST,
//                ex.getMessage()
//        );
//    }

    @ExceptionHandler({
            CustomAuthenticationException.class
    })
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ApiResponse<LocalDateTime> unauthExceptionHandler(Exception ex) {
        log.error(ex.getMessage(), ex);

        return ApiResponse.fail(
                HttpStatus.UNAUTHORIZED,
                ex.getMessage()
        );
    }

    @ExceptionHandler({
            CustomNotFoundException.class
    })
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<LocalDateTime> notFoundExceptionHandler(Exception ex) {
        log.error(ex.getMessage(), ex);

        return ApiResponse.fail(
                HttpStatus.NOT_FOUND,
                ex.getMessage()
        );
    }
    @ExceptionHandler({
            CustomConflictException.class
    })
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiResponse<LocalDateTime> conflictExceptionHandler(Exception ex) {
        log.error(ex.getMessage(), ex);

        return ApiResponse.fail(
                HttpStatus.CONFLICT,
                ex.getMessage()
        );
    }

//    @ExceptionHandler({
//    })
//    @ResponseStatus(HttpStatus.CONFLICT)
//    public ApiResponse<LocalDateTime> conflictExceptionHandler(Exception ex) {
//        log.error(ex.getMessage(), ex);
//
//        return ApiResponse.fail(
//                HttpStatus.CONFLICT,
//                ex.getMessage()
//        );
//    }
}
