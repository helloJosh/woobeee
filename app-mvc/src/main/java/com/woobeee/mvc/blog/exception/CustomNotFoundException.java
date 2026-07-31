package com.woobeee.mvc.blog.exception;

public class CustomNotFoundException extends RuntimeException {
    public CustomNotFoundException(ErrorCode message) {
        super(message.name());
    }
}
