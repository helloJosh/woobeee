package com.woobeee.mvc.schedule.exception;

public class ScheduleException extends RuntimeException {
    private final ScheduleErrorCode errorCode;

    public ScheduleException(ScheduleErrorCode errorCode) {
        super(errorCode.reason());
        this.errorCode = errorCode;
    }

    public ScheduleErrorCode errorCode() {
        return errorCode;
    }
}
