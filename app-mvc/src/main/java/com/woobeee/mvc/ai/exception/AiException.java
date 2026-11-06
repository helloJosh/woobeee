package com.woobeee.mvc.ai.exception;

public class AiException extends RuntimeException {
    private final AiErrorCode errorCode;

    public AiException(AiErrorCode errorCode) {
        super(errorCode.reason());
        this.errorCode = errorCode;
    }

    public AiErrorCode errorCode() {
        return errorCode;
    }
}
