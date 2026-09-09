package com.woobeee.mvc.blog.exception;

public class CustomBadRequestException extends RuntimeException {
    public CustomBadRequestException(ErrorCode message) {
        super(message.name());
    }
}
