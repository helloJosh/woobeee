package com.woobeee.mvc.blog.exception;

public class CustomConflictException extends RuntimeException{
    public CustomConflictException(ErrorCode message) {
        super(message.name());
    }
}
