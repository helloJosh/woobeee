package com.woobeee.game.api.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 게임 API 가 실패 응답에 싣는 코드 목록.
 *
 * <p>이 코드는 프론트와의 <b>계약</b>이다. {@code front/lib/api.ts} 는 실패 응답의
 * {@code header.message} 를 코드로 읽고 {@code front/lib/errors/error-messages.ts} 에서
 * 한국어 문구를 찾는다. 지도에 없는 코드는 전부 "예기치 못한 오류가 발생했습니다." 한 줄로
 * 뭉개지므로, 여기에 값을 추가하면 그 파일에도 함께 추가해야 한다.
 *
 * <p>{@code reason} 은 사람이 읽는 영어 설명으로, 로그와 테스트용이다. 응답 본문에 나가는 것은
 * {@code code} 뿐이다 — 내부 문구를 그대로 노출하지 않기 위해서다.
 *
 * <p>패키지가 {@code api} 아래인 것은 의도적이다. 이 목록은 도메인 규칙이 아니라 바깥으로
 * 나가는 계약이다. 도메인 서비스들은 이미 {@code ResponseStatusException}(웹 타입)을 던지고
 * 있으므로 여기에 기대는 것이 결합을 새로 만드는 것은 아니다.
 */
public enum GameErrorCode {
    /* ===== 방 · 초대 ===== */
    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "game_roomNotFound", "Room not found"),
    INVALID_INVITE_CODE(HttpStatus.FORBIDDEN, "game_invalidInviteCode", "Invalid invite code"),
    ROOM_FULL(HttpStatus.CONFLICT, "game_roomFull", "Room is full"),
    GAME_ALREADY_STARTED(HttpStatus.CONFLICT, "game_gameAlreadyStarted", "Game already started"),

    /* ===== 신원 ===== */
    INVALID_NICKNAME(HttpStatus.BAD_REQUEST, "game_invalidNickname", "Nickname must be 1-20 visible characters"),
    NICKNAME_TAKEN(HttpStatus.CONFLICT, "game_nicknameTaken", "Nickname is already used in this room"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "game_unauthorized", "Access token is required"),
    MEMBER_NOT_FOUND(HttpStatus.UNAUTHORIZED, "game_memberNotFound", "Member not found"),
    INVALID_GAME_TOKEN(HttpStatus.UNAUTHORIZED, "game_invalidGameToken", "Invalid game token"),

    /* ===== 진행 ===== */
    NOT_A_MEMBER(HttpStatus.FORBIDDEN, "game_notAMember", "Not a member of this room"),
    NOT_HOST(HttpStatus.FORBIDDEN, "game_notHost", "Only the host can start the game"),
    NOT_ENOUGH_PLAYERS(HttpStatus.CONFLICT, "game_notEnoughPlayers", "At least two players are required"),
    OMOK_REQUIRES_TWO(HttpStatus.CONFLICT, "game_omokRequiresTwo", "Omok requires exactly two players"),
    NOT_ALL_READY(HttpStatus.CONFLICT, "game_notAllReady", "All players must be ready"),

    /* ===== 결과 · 기보 ===== */
    NOT_A_PARTICIPANT(HttpStatus.FORBIDDEN, "game_notAParticipant", "Not a participant of this game"),
    REPLAY_UNAVAILABLE(HttpStatus.NOT_FOUND, "game_replayUnavailable", "Replay is unavailable"),

    /* ===== 폴백 ===== */
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "game_badRequest", "Malformed request"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "game_forbidden", "Forbidden"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "game_notFound", "Not found"),
    CONFLICT(HttpStatus.CONFLICT, "game_conflict", "Conflict"),
    UNEXPECTED(HttpStatus.INTERNAL_SERVER_ERROR, "game_unexpected", "Unexpected server error");

    private final HttpStatus status;
    private final String code;
    private final String reason;

    GameErrorCode(HttpStatus status, String code, String reason) {
        this.status = status;
        this.code = code;
        this.reason = reason;
    }

    public HttpStatus status() {
        return status;
    }

    /** 응답 본문의 {@code header.message} 로 나가는 값. */
    public String code() {
        return code;
    }

    /** 로그와 예외 메시지용 영어 설명. 응답에는 나가지 않는다. */
    public String reason() {
        return reason;
    }

    public GameException asException() {
        return new GameException(this);
    }

    /**
     * 코드를 달지 않고 올라온 {@code ResponseStatusException}(프레임워크가 만든 400·405·415 등)
     * 을 상태만 보고 폴백 코드로 옮긴다. 구체적인 코드를 잃긴 하지만, 봉투 모양은 지켜진다.
     */
    public static GameErrorCode fromStatus(HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return UNEXPECTED;
        }
        return switch (status.value()) {
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 409 -> CONFLICT;
            default -> BAD_REQUEST;
        };
    }
}
