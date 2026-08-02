import { getFriendlyErrorMessage } from "@/lib/errors/error-utils"
import {
    isServerMessage,
    type GameEndPayload,
    type OmokRejectionReason,
    type ServerMessage,
    type SocketStatus,
} from "@/lib/game-socket"
import type { ParticipantView, RoomStatus } from "@/lib/types"

/**
 * 오목 플레이 화면의 판단 전부. game-join.ts / room-sidebar.ts 와 같은 이유로 React 에
 * 의존하지 않는다 — 소켓 메시지를 화면 상태로 접는 일은 조용히 틀리면 몇 주를 그대로 갈
 * 종류라, 컴포넌트가 아니라 여기서 순수 함수와 리듀서로 두고 (테스트 러너가 들어오면)
 * 그대로 고정한다. app/game/omok/[roomId]/page.tsx 는 이 모듈의 결과를 그리기만 한다.
 *
 * <p>서버 계약의 단일 출처는 app-webflux 의 `OmokGameSink` / `OmokGame` /
 * `RoomCommandDispatcher` 다. 아래 주석의 규칙들은 그 코드에서 옮겨 왔다.
 */

/** `OmokBoard` 의 한 변. 서버의 `OmokBoard.SIZE` 와 같다. */
export const OMOK_BOARD_SIZE = 15

export type OmokStone = "BLACK" | "WHITE"

export interface OmokPlacement {
    x: number
    y: number
    color: OmokStone
}

export interface OmokRoomState {
    participants: ParticipantView[]
    hostParticipantId: string
    status: RoomStatus
    placements: OmokPlacement[]
    /** 지금 둘 차례인 사람. 게임 전/후에는 null 이다. */
    turnParticipantId: string | null
    /** 서버가 준 ISO-8601 마감시각. 승리 착수 뒤에는 없다. */
    turnDeadline: string | null
    /** GAME_END 페이로드 그대로. 문구는 selfParticipantId 를 아는 쪽에서 만든다. */
    outcome: GameEndPayload | null
    /** 착수 거부·서버 오류 같은 일회성 안내. */
    notice: string | null
    /**
     * 아직 응답을 못 받은 내 OMOK_PLACE 의 seq.
     *
     * <p>OMOK_REJECTED 는 방 전체로 브로드캐스트된다(OmokGameSink.onGameCommand 의
     * `roomHub.broadcast`). 그대로 그리면 상대가 금수를 뒀을 때 내 화면에도 "삼삼은 흑의
     * 금수입니다" 가 뜬다 — 내가 한 적 없는 일에 대한 안내다. 페이로드에는 누가 뒀는지가
     * 없지만 `ServerMessage.ack` 이 그 명령의 seq 를 ackSeq 로 돌려주므로, 내가 보낸
     * OMOK_PLACE 의 seq 와 맞을 때만 보여 준다.
     */
    pendingPlaceSeq: number | null
}

export const initialOmokRoomState: OmokRoomState = {
    participants: [],
    hostParticipantId: "",
    status: "WAITING",
    placements: [],
    turnParticipantId: null,
    turnDeadline: null,
    outcome: null,
    notice: null,
    pendingPlaceSeq: null,
}

export type OmokRoomAction =
    | { type: "message"; message: ServerMessage }
    /** 방이 바뀌었거나 소켓을 새로 연다. 이전 방의 판을 남기지 않는다. */
    | { type: "reset" }
    /** OMOK_PLACE 를 보냈다. GameSocket.send 가 돌려준 seq 를 그대로 넣는다. */
    | { type: "place-sent"; seq: number }

export function reduceOmokRoom(state: OmokRoomState, action: OmokRoomAction): OmokRoomState {
    switch (action.type) {
        case "reset":
            return initialOmokRoomState
        case "place-sent":
            return { ...state, pendingPlaceSeq: action.seq }
        case "message":
            return applyServerMessage(state, action.message)
    }
}

/**
 * payload 는 unknown 이다. isServerMessage 로 좁히는 것이 그것을 읽는 유일한 방법이다 —
 * `switch (message.type)` 로는 좁혀지지 않아 승리 착수에 존재하지도 않는 `payload.nextTurn`
 * 을 읽는 코드가 그대로 통과한다. 되돌리지 말 것.
 */
function applyServerMessage(state: OmokRoomState, message: ServerMessage): OmokRoomState {
    if (isServerMessage(message, "ROOM_STATE")) {
        return {
            ...state,
            participants: message.payload.participants,
            hostParticipantId: message.payload.hostParticipantId,
            // 서버는 게임이 끝나도 방 상태를 IN_PROGRESS 로 둔다(CLAUDE.md 의 known-gap G3).
            // 그대로 받아들이면 GAME_END 로 FINISHED 가 된 화면이 다음 ROOM_STATE(상대가
            // 접속을 끊는 것만으로도 온다) 한 번에 "진행 중" 으로 되돌아간다.
            //
            // 사이드바의 준비·시작 버튼은 이것과 무관하다 — RoomSidebar 는 그 블록을
            // status === "WAITING" 에서만 그리고 서버는 시작 뒤에 WAITING 을 보내지 않는다.
            // 실제로 어긋나는 것은 이 status 를 읽는 두 곳이다: describeTurn 이 끝난 판에
            // 대해 "상대 차례" 를 만들고(지금은 statusLine 에서 outcome 이 먼저 걸려 가려질
            // 뿐이다 — 그 우연에 기대고 싶지 않다), canPlaceStone 이 IN_PROGRESS 를 참으로
            // 보게 된다. 끝난 판은 끝난 판으로 남긴다.
            status: state.status === "FINISHED" ? "FINISHED" : message.payload.status,
        }
    }

    if (isServerMessage(message, "GAME_START")) {
        return {
            ...state,
            status: "IN_PROGRESS",
            placements: [],
            // GAME_START 페이로드에는 roomId 뿐이라 첫 차례가 실려 오지 않는다. 규칙으로
            // 세운다: OmokGameSink.onStart 가 흑을 방장에게 주고(`String black =
            // room.hostParticipantId()`), OmokGame 은 `Stone turn = Stone.BLACK` 으로
            // 시작한다. 이 줄이 없으면 turnParticipantId 가 null 이라 두 사람 모두 판이
            // 잠긴 채 아무도 첫 수를 둘 수 없다.
            turnParticipantId: state.hostParticipantId || null,
            // 첫 수의 마감시각은 서버만 안다(시작 시각 + 60초). 첫 OMOK_MOVED 가 실제 값을
            // 실어 올 때까지 비워 둔다 — 추측한 시각을 보여 주는 것보다 낫다.
            turnDeadline: null,
            outcome: null,
            notice: null,
            pendingPlaceSeq: null,
        }
    }

    // 재접속 경로. ROOM_STATE 바로 뒤에 서버가 판 전체를 다시 보내 준다. 통째로 갈아 끼운다 —
    // 끊긴 동안 놓친 수가 있으므로 지금 판에 이어 붙이면 어긋난다.
    if (isServerMessage(message, "GAME_SNAPSHOT")) {
        const snapshot = message.payload
        // 방의 게임 종류는 고정이라 실제로는 항상 OMOK 이지만, 이 분기가 없으면 타입이
        // moves 를 읽게 해 주지 않는다. 그게 이 유니온의 목적이다.
        if (snapshot.gameType !== "OMOK") {
            return state
        }
        return {
            ...state,
            placements: snapshot.moves.map(({ x, y, color }) => ({ x, y, color })),
            turnParticipantId: snapshot.nextTurn,
            turnDeadline: snapshot.turnDeadline,
            // notice 는 지우지 않는다. 스냅샷은 누가 재접속하든 방 전체로 나가므로
            // (OmokGameSink.onRejoin 은 세션 단위 전송이 없어 broadcast 한다) 여기서 지우면
            // 상대가 새로고침하는 것만으로 내 금수 안내가 사라진다. 판만 갈아 끼운다.
            //
            // 반면 pendingPlaceSeq 는 비운다. 내가 재접속한 경우 끊기기 전에 보낸 착수의
            // 응답은 영영 오지 않으므로, 남겨 두면 그 seq 가 다음 착수를 막는 잠금으로 굳는다.
            pendingPlaceSeq: null,
        }
    }

    if (isServerMessage(message, "OMOK_MOVED")) {
        const move = message.payload
        const placements = [...state.placements, { x: move.x, y: move.y, color: move.color }]
        // 승리 착수에는 nextTurn·turnDeadline 이 아예 오지 않는다. 이 분기를 빼면 판은
        // 끝났는데 차례 표시만 살아 있는 화면이 된다.
        if (move.nextTurn === undefined) {
            return {
                ...state,
                placements,
                turnParticipantId: null,
                turnDeadline: null,
                notice: null,
                pendingPlaceSeq: null,
            }
        }
        return {
            ...state,
            placements,
            turnParticipantId: move.nextTurn,
            turnDeadline: move.turnDeadline,
            notice: null,
            pendingPlaceSeq: null,
        }
    }

    if (isServerMessage(message, "OMOK_REJECTED")) {
        // 내가 보낸 착수에 대한 거부일 때만 보여 준다. pendingPlaceSeq 주석 참고.
        if (state.pendingPlaceSeq === null || message.ackSeq !== state.pendingPlaceSeq) {
            return state
        }
        return {
            ...state,
            notice: describeOmokRejection(message.payload.reason),
            pendingPlaceSeq: null,
        }
    }

    if (isServerMessage(message, "GAME_END")) {
        return {
            ...state,
            status: "FINISHED",
            turnParticipantId: null,
            turnDeadline: null,
            outcome: message.payload,
            notice: null,
            pendingPlaceSeq: null,
        }
    }

    if (isServerMessage(message, "ERROR")) {
        // payload.message 는 서버 로그용 영어다. 사용자에게 보여줄 문구는 payload.code
        // (`game_*`)로 error-messages.ts 에서 찾는다 — HTTP 실패와 같은 지도다.
        return {
            ...state,
            notice: getFriendlyErrorMessage(message.payload.code),
            // ERROR 도 내 착수에 대한 응답일 수 있다 — RoomCommandDispatcher.gameCommand 는
            // guard(roomId, message.seq(), ...) 로 감싸여 있어 실패하면 그 명령의 seq 가
            // ackSeq 로 돌아온다. 여기서 비워 두지 않으면 응답을 이미 받은 seq 가 영영 남고,
            // seq 는 클라이언트마다 따로 0 부터 세므로 나중에 상대의 OMOK_PLACE 가 같은 값을
            // 쓰는 순간 상대의 금수 안내가 내 화면에 뜬다 — pendingPlaceSeq 가 막으려던 바로
            // 그 일이다. 내 것이 아닌 ERROR 는 대기 중인 seq 를 건드리지 않는다.
            pendingPlaceSeq: message.ackSeq === state.pendingPlaceSeq ? null : state.pendingPlaceSeq,
        }
    }

    // 모르는 타입은 버리지 않고 그냥 지나간다 — 서버가 새 메시지를 추가해도 화면은 살아 있다.
    return state
}

/**
 * Record 로 두는 이유: 서버(OmokGame/RenjuRule)가 새 거부 사유를 추가하면
 * {@link OmokRejectionReason} 이 늘어나고, 여기 빠진 항목이 컴파일 오류로 잡힌다.
 * switch 였다면 조용히 기본 문구로 떨어진다.
 */
const OMOK_REJECTION_TEXT: Record<OmokRejectionReason, string> = {
    GAME_FINISHED: "이미 끝난 게임입니다.",
    NOT_YOUR_TURN: "아직 차례가 아닙니다.",
    OUT_OF_BOUNDS: "판 밖입니다.",
    OCCUPIED: "이미 돌이 놓인 자리입니다.",
    DOUBLE_THREE: "삼삼은 흑의 금수입니다.",
    DOUBLE_FOUR: "사사는 흑의 금수입니다.",
    OVERLINE: "장목(6목 이상)은 흑의 금수입니다.",
}

export function describeOmokRejection(reason: string): string {
    return OMOK_REJECTION_TEXT[reason as OmokRejectionReason] ?? "둘 수 없는 자리입니다."
}

export interface SelfIdentityInput {
    /** tokenManager.getMemberId() / useAuth().memberId. 로그인하지 않았으면 null. */
    memberId: number | null
    /** 이 방의 게스트 토큰과 함께 저장해 둔 participantId. readStoredGuestIdentity 참고. */
    guestParticipantId: string | null
    /** 마지막 ROOM_STATE 의 명단. 아직 없으면 빈 배열. */
    participants: ParticipantView[]
}

/**
 * 명단에서 "나" 를 찾는다.
 *
 * <p>서버는 내 participantId 를 따로 알려주지 않지만 규칙으로 계산할 수 있다 —
 * `GameParticipant.member` 가 `"m:" + memberId`, `GameParticipant.guest` 가
 * `"g:" + guestId` 를 만든다. 회원은 memberId 로 그대로 만들 수 있고, 게스트는 토큰을
 * 발급받을 때 서버가 돌려준 participantId 를 토큰 옆에 저장해 둔다.
 *
 * <p>"명단의 마지막 사람이 나" 같은 추측은 쓰지 않는다. 방장이 새로고침하면 명단은
 * [방장, 손님] 이라 마지막은 내가 아니고, 그 순간 화면 전체가 상대의 것이 된다 — 판이 내
 * 차례에 잠기고 상대 차례에 열리며, 방장인데 시작 버튼이 사라진다.
 *
 * <p>게스트 토큰이 회원 토큰보다 우선인 것은 decideJoinGate 의 토큰 우선순위와 같다.
 * 실제로 JOIN 에 실려 간 토큰이 게스트 토큰이므로 서버가 보는 나도 그쪽이다.
 */
export function resolveSelfParticipantId(input: SelfIdentityInput): string | null {
    const candidate = input.guestParticipantId
        ?? (input.memberId !== null ? `m:${input.memberId}` : null)

    if (candidate && input.participants.some((p) => p.participantId === candidate)) {
        return candidate
    }
    if (input.participants.length === 0) {
        return candidate
    }
    // 명단에 후보가 없다. ROOM_STATE 는 내 참가가 확정된 뒤에만 오므로 나는 반드시 명단에
    // 있다 — 방에 한 사람뿐이면 그게 나다. (localStorage 의 authMemberId 가 낡아 후보가
    // 어긋난 경우를 여기서 건져낸다.)
    if (input.participants.length === 1) {
        return input.participants[0].participantId
    }
    return candidate
}

/**
 * 내 돌 색. 흑은 방장이다(OmokGameSink.onStart). 오목은 정확히 두 명이므로 방장이 아니면
 * 백이다. 어느 쪽인지 화면에 쓰지 않으면 첫 수를 두기 전까지 자기 색을 알 방법이 없다.
 *
 * <p>"방장이 아니다" 만으로 백이라고 단정하지 않는다 — resolveSelfParticipantId 가 명단에
 * 없는 후보를 돌려줄 수 있고(회원 식별자를 만들었지만 아직 ROOM_STATE 가 오지 않았거나
 * localStorage 가 낡은 경우), 그때 "내 돌 백" 은 근거 없는 단정이다. 명단에서 나를 실제로
 * 찾았을 때만 답한다.
 */
export function myStoneColor(
    state: OmokRoomState,
    selfParticipantId: string | null
): OmokStone | null {
    if (!selfParticipantId || !state.hostParticipantId) {
        return null
    }
    if (!state.participants.some((p) => p.participantId === selfParticipantId)) {
        return null
    }
    return selfParticipantId === state.hostParticipantId ? "BLACK" : "WHITE"
}

/**
 * 판을 열어도 되는가. `joined` 를 요구하는 것이 핵심이다 — `open` 은 핸드셰이크만 끝난
 * 상태라 그때 보낸 착수는 서버가 JOIN 전 메시지로 버린다(GameWebSocketHandler.handleText).
 */
export function canPlaceStone(
    state: OmokRoomState,
    selfParticipantId: string | null,
    socketStatus: SocketStatus
): boolean {
    return (
        socketStatus === "joined"
        && state.status === "IN_PROGRESS"
        && selfParticipantId !== null
        && state.turnParticipantId === selfParticipantId
    )
}

/** 게임 종료 문구. 승자가 없으면 winnerParticipantId 는 null 이 아니라 빈 문자열이다. */
export function describeGameOutcome(
    outcome: GameEndPayload,
    selfParticipantId: string | null
): string {
    if (!outcome.winnerParticipantId) {
        return "게임 종료 — 승부가 나지 않았습니다."
    }
    if (selfParticipantId && outcome.winnerParticipantId === selfParticipantId) {
        return "게임 종료 — 승리했습니다."
    }
    const winner = outcome.ranks.find((entry) => entry.rank === 1)?.displayName ?? "-"
    return `게임 종료 — 승자 ${winner}`
}

/**
 * 차례 안내. 서버가 준 마감시각을 카운트다운이 아니라 시각 그대로 보여 주는 이유는 서버가
 * 아직 이 제한시간을 강제하지 않기 때문이다(CLAUDE.md 의 known-gap G1) — 0 에 닿아도 아무
 * 일도 일어나지 않는 카운트다운은 거짓말이 된다.
 */
export function describeTurn(state: OmokRoomState, myTurn: boolean): string {
    if (state.status !== "IN_PROGRESS") {
        return ""
    }
    const hint = state.turnDeadline
        ? ` · 제한 ${new Date(state.turnDeadline).toLocaleTimeString()}`
        : ""
    return `${myTurn ? "내 차례" : "상대 차례"}${hint}`
}

/**
 * 더 이상 아무것도 기다릴 것이 없는 상태. createGameSocket 의 `settled` 와 같은 둘이다 —
 * 이 상태에서는 소켓이 다시 붙지 않으므로 화면도 진행 중임을 뜻하는 표시(스피너)를 걷어야
 * 한다. 돌아가는 스피너는 "곧 될 것" 이라는 약속인데 여기서는 지켜지지 않는다.
 */
export function isSocketSettled(status: SocketStatus): boolean {
    return status === "rejected" || status === "closed"
}

/**
 * 연결 상태 안내. `joined` 일 때만 null 이고, 나머지는 전부 지금 판이 신뢰할 수 없는
 * 상태라는 뜻이라 배너로 말해 준다.
 */
export function describeSocketStatus(status: SocketStatus, errorCode?: string): string | null {
    switch (status) {
        case "joined":
            return null
        case "connecting":
        case "open":
            return "연결하는 중입니다…"
        case "reconnecting":
            return "연결이 끊겼습니다. 다시 연결하는 중입니다…"
        case "rejected":
            // 서버가 거절 직전에 보내 준 ERROR 프레임의 code. 프레임이 오기 전에 연결이
            // 끊기면 없을 수 있다.
            return errorCode
                ? getFriendlyErrorMessage(errorCode)
                : "방에 입장할 수 없습니다. 링크를 다시 확인해 주세요."
        case "closed":
            return "서버와의 연결이 끊어졌습니다. 페이지를 새로고침해 주세요."
    }
}
