package com.woobeee.core.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Builder
public record ApiResponse<T>(
        Header header,
        T data
) {

    @Builder
    public record Header(
            boolean isSuccessful,
            String message,
            int resultCode
    ) {}

    /* ===== success ===== */

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(
                new Header(true, message, HttpStatus.OK.value()),
                data
        );
    }

    public static <T> ApiResponse<T> success(String message) {
        return new ApiResponse<>(
                new Header(true, message, HttpStatus.OK.value()),
                null
        );
    }

    public static <T> ApiResponse<T> createSuccess(T data, String message) {
        return new ApiResponse<>(
                new Header(true, message, HttpStatus.CREATED.value()),
                data
        );
    }

    public static <T> ApiResponse<T> createSuccess(String message) {
        return new ApiResponse<>(
                new Header(true, message, HttpStatus.CREATED.value()),
                null
        );
    }

    public static <T> ApiResponse<T> deleteSuccess(String message) {
        return new ApiResponse<>(
                new Header(true, message, HttpStatus.NO_CONTENT.value()),
                null
        );
    }

    /* ===== fail ===== */

    public static ApiResponse<LocalDateTime> fail(HttpStatus errorCode, String message) {
        return new ApiResponse<>(
                new Header(false, message, errorCode.value()),
                LocalDateTime.now()
        );
    }
}
