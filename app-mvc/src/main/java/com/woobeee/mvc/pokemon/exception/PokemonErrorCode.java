package com.woobeee.mvc.pokemon.exception;

import org.springframework.http.HttpStatus;

/**
 * pokemon API 가 실패 응답에 싣는 코드 목록. ScheduleErrorCode 와 같은 계약 방식이다:
 * front/lib/api.ts 는 실패 응답의 header.message 를 코드로 읽고
 * front/lib/errors/error-messages.ts 에서 문구를 찾는다. 값을 추가하면 그 파일에도
 * 함께 추가해야 한다 (PokemonErrorCodeTest 가 양방향으로 강제한다).
 */
public enum PokemonErrorCode {
    MEMBER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "pokemon_memberNotFound", "Member not found"),
    MANAGER_REQUIRED(HttpStatus.FORBIDDEN, "pokemon_managerRequired",
            "Only a group-buy manager can do that"),
    NOT_YOURS(HttpStatus.FORBIDDEN, "pokemon_notYours", "You can only cancel your own request"),

    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "pokemon_orderNotFound", "Order not found"),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "pokemon_commentNotFound", "Comment not found"),
    PRODUCT_NOT_FOUND(HttpStatus.NOT_FOUND, "pokemon_productNotFound",
            "A product in this request is no longer on sale"),
    DUPLICATE_PRODUCT_NAME(HttpStatus.BAD_REQUEST, "pokemon_duplicateProductName",
            "A product with that name already exists"),
    PRODUCT_IN_USE(HttpStatus.BAD_REQUEST, "pokemon_productInUse",
            "This product is on an existing request — take it off sale instead of deleting it"),
    EMPTY_ORDER(HttpStatus.BAD_REQUEST, "pokemon_emptyOrder", "Pick at least one product"),
    DUPLICATE_PRODUCT(HttpStatus.BAD_REQUEST, "pokemon_duplicateProduct",
            "Each product may appear at most once — use quantity instead"),
    NAME_REQUIRED(HttpStatus.BAD_REQUEST, "pokemon_nameRequired",
            "Guests must provide a name"),
    EMPTY_COMMENT(HttpStatus.BAD_REQUEST, "pokemon_emptyComment", "Write something first"),
    ALREADY_SETTLED(HttpStatus.BAD_REQUEST, "pokemon_alreadySettled",
            "Only a request that has not been prepared yet can be withdrawn"),

    RATE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "pokemon_rateUnavailable",
            "Exchange rate is temporarily unavailable"),

    BAD_REQUEST(HttpStatus.BAD_REQUEST, "pokemon_badRequest", "Malformed request"),
    UNEXPECTED(HttpStatus.INTERNAL_SERVER_ERROR, "pokemon_unexpected", "Unexpected server error");

    private final HttpStatus status;
    private final String code;
    private final String reason;

    PokemonErrorCode(HttpStatus status, String code, String reason) {
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

    public PokemonException asException() {
        return new PokemonException(this);
    }
}
