package com.woobeee.mvc.blog.exception;

public class CustomInternalServerException extends RuntimeException {
    public CustomInternalServerException(ErrorCode message) {
        super(message.name());
    }
}
