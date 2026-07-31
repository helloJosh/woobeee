package com.woobeee.mvc.blog.exception;

public class CustomAuthenticationException extends RuntimeException{
    public CustomAuthenticationException(ErrorCode message) {
        super(message.name());
    }
}
