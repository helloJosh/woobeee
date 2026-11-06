package com.woobeee.mvc.ai.exception;

import org.springframework.http.HttpStatus;

/**
 * ai API 가 실패 응답에 싣는 코드 목록. PokemonErrorCode 와 같은 계약이다: front/lib/api.ts 는 실패 응답의
 * header.message 를 코드로 읽고 front/lib/errors/error-messages.ts 에서 문구를 찾는다. 값을 추가하면 그
 * 파일에도 함께 추가해야 한다(AiErrorCodeTest 가 양방향으로 강제한다).
 */
public enum AiErrorCode {
    LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "ai_loginRequired", "Login is required"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "ai_notFound", "Request not found"),
    REQUEST_IN_PROGRESS(HttpStatus.CONFLICT, "ai_requestInProgress",
            "You already have a request in progress"),
    EMPTY_REQUEST(HttpStatus.BAD_REQUEST, "ai_emptyRequest", "Write something or attach an image"),
    TOO_MANY_IMAGES(HttpStatus.BAD_REQUEST, "ai_tooManyImages", "Too many images"),
    IMAGE_TOO_LARGE(HttpStatus.BAD_REQUEST, "ai_imageTooLarge", "An image is too large"),
    UNSUPPORTED_IMAGE(HttpStatus.BAD_REQUEST, "ai_unsupportedImage", "Only png, jpeg and webp images are accepted"),
    NOT_RETRYABLE(HttpStatus.CONFLICT, "ai_notRetryable", "Only a failed request can be retried"),
    ACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "ai_actionNotFound", "Proposal not found"),
    ACTION_ALREADY_DONE(HttpStatus.CONFLICT, "ai_actionAlreadyDone", "That proposal was already decided"),
    APPLY_FORBIDDEN(HttpStatus.FORBIDDEN, "ai_applyForbidden", "You are not allowed to register that"),
    WORKER_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "ai_workerUnauthorized", "Worker secret is missing or wrong"),
    STALE_ATTEMPT(HttpStatus.CONFLICT, "ai_staleAttempt", "This attempt is no longer current"),
    NOT_CLAIMABLE(HttpStatus.CONFLICT, "ai_notClaimable", "This request cannot be claimed now"),

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "ai_badRequest", "Malformed request"),
    UNEXPECTED(HttpStatus.INTERNAL_SERVER_ERROR, "ai_unexpected", "Unexpected error");

    private final HttpStatus status;
    private final String code;
    private final String reason;

    AiErrorCode(HttpStatus status, String code, String reason) {
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

    public AiException asException() {
        return new AiException(this);
    }
}
