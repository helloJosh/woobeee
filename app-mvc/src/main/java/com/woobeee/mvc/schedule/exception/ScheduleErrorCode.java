package com.woobeee.mvc.schedule.exception;

import org.springframework.http.HttpStatus;

/**
 * schedule API 가 실패 응답에 싣는 코드 목록. GameErrorCode 와 같은 계약 방식이다:
 * front/lib/api.ts 는 실패 응답의 header.message 를 코드로 읽고
 * front/lib/errors/error-messages.ts 에서 문구를 찾는다. 값을 추가하면 그 파일에도
 * 함께 추가해야 한다 (ScheduleErrorCodeTest 가 양방향으로 강제한다).
 */
public enum ScheduleErrorCode {
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "schedule_unauthorized", "Access token is required"),
    MEMBER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "schedule_memberNotFound", "Member not found"),

    PROJECT_NOT_FOUND(HttpStatus.NOT_FOUND, "schedule_projectNotFound", "Project not found or not yours"),
    MILESTONE_NOT_FOUND(HttpStatus.NOT_FOUND, "schedule_milestoneNotFound", "Milestone not found"),
    TASK_NOT_FOUND(HttpStatus.NOT_FOUND, "schedule_taskNotFound", "Task not found"),

    CROSS_PROJECT(HttpStatus.BAD_REQUEST, "schedule_crossProject", "Referenced node belongs to another project"),
    DEPTH_EXCEEDED(HttpStatus.BAD_REQUEST, "schedule_depthExceeded", "Milestone depth may not exceed 5"),
    CYCLE(HttpStatus.BAD_REQUEST, "schedule_cycle", "A milestone cannot move under itself or its descendant"),
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "schedule_invalidDateRange", "endDate must not be before startDate"),
    INVALID_COLOR(HttpStatus.BAD_REQUEST, "schedule_invalidColor", "Color must be #RRGGBB"),

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "schedule_badRequest", "Malformed request"),
    UNEXPECTED(HttpStatus.INTERNAL_SERVER_ERROR, "schedule_unexpected", "Unexpected server error");

    private final HttpStatus status;
    private final String code;
    private final String reason;

    ScheduleErrorCode(HttpStatus status, String code, String reason) {
        this.status = status;
        this.code = code;
        this.reason = reason;
    }

    public HttpStatus status() {
        return status;
    }

    /** 응답 본문의 header.message 로 나가는 값. */
    public String code() {
        return code;
    }

    /** 로그와 예외 메시지용 영어 설명. 응답에는 나가지 않는다. */
    public String reason() {
        return reason;
    }

    public ScheduleException asException() {
        return new ScheduleException(this);
    }
}
