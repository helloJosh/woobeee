"use client"

import { gameSocketUrl } from "@/lib/game-config"
import type { GameType, ParticipantView, RoomStatus } from "@/lib/types"

/**
 * /ws/game 소켓 하나를 소유하는 유일한 모듈. 화면은 이 모듈이 넘겨 주는 이벤트만 구독하고
 * 절대 raw WebSocket 을 직접 열지 않는다 — 재접속과 seq 관리가 화면마다 갈라지면 두 게임의
 * 동작이 조금씩 달라지기 때문이다.
 *
 * <p>서버 계약의 단일 출처는 app-webflux 의
 * `com.woobeee.game.ws.ClientMessage` / `ServerMessage` 와 각 싱크의 broadcast 호출이다.
 * 아래 타입은 그 코드에서 필드 단위로 옮겨 왔다.
 */

// ---------------------------------------------------------------------------
// 클라이언트 -> 서버
// ---------------------------------------------------------------------------

/** 서버: `record ClientMessage(String type, Long seq, JsonNode payload)`. */
export interface ClientMessage<T = unknown> {
    type: string
    seq: number
    payload: T
}

export type DodgeDirection = "UP" | "DOWN" | "LEFT" | "RIGHT"

/**
 * GameWebSocketHandler.handleText 가 실제로 분기하는 타입들.
 * JOIN 이전에는 JOIN 외의 모든 메시지가 버려지고, JOIN 이후의 중복 JOIN 도 무시된다.
 * 여기 없는 타입은 dispatcher.gameCommand 로 흘러 방의 게임 싱크가 처리한다.
 */
export interface ClientMessagePayloads {
    /** 인증·입장. 세 필드 중 하나라도 없으면 서버가 세션을 닫는다. */
    JOIN: { roomId: string; inviteCode: string; token: string }
    /** 유예 없이 즉시 이탈. */
    LEAVE: Record<string, never>
    READY: { ready: boolean }
    /** 방장만 가능. 페이로드를 읽지 않는다. */
    START: Record<string, never>
    OMOK_PLACE: { x: number; y: number }
    DODGE_MOVE: { direction: DodgeDirection }
}

export type ClientMessageType = keyof ClientMessagePayloads

// ---------------------------------------------------------------------------
// 서버 -> 클라이언트
// ---------------------------------------------------------------------------

/** RoomStateProjector.project — memberId 는 의도적으로 빠져 있다. */
export interface RoomStatePayload {
    gameType: GameType
    hostParticipantId: string
    status: RoomStatus
    participants: ParticipantView[]
}

/** RoomCommandDispatcher.start — roomId 뿐이다. */
export interface GameStartPayload {
    roomId: string
}

export interface GameRankEntry {
    participantId: string
    displayName: string
    rank: number
}

/**
 * 오목·장애물피하기가 공유하는 종료 페이로드. gameResultId 는 들어 있지 않다 — 서버는 결과
 * 저장(S3 왕복 포함)을 기다리지 않고 먼저 방송한다. 승자가 없으면 winnerParticipantId 는
 * null 이 아니라 빈 문자열이다.
 */
export interface GameEndPayload {
    winnerParticipantId: string
    ranks: GameRankEntry[]
}

export interface OmokMoveBase {
    participantId: string
    x: number
    y: number
    color: "BLACK" | "WHITE"
}

/** 계속되는 착수. 다음 차례와 그 마감시각(Instant.toString(), ISO-8601)이 실린다. */
export interface OmokMovePlaced extends OmokMoveBase {
    nextTurn: string
    turnDeadline: string
}

/** 승리 착수. 다음 차례가 없으므로 두 필드가 아예 오지 않는다. */
export interface OmokMoveWon extends OmokMoveBase {
    nextTurn?: never
    turnDeadline?: never
}

/**
 * OmokGameSink.onGameCommand 는 같은 OMOK_MOVED 를 두 모양으로 보낸다 — PLACED 는
 * nextTurn·turnDeadline 을 싣고(:131-142), 승리 착수는 participantId·x·y·color 만
 * 싣는다(:148-157). 유니온으로 둔 이유는 그 차이를 타입이 강제하게 하려는 것이다:
 * `payload.nextTurn` 은 `string | undefined` 이므로 차례 상태에 그대로 대입할 수 없고,
 * 호출자가 "마지막 수였다" 를 반드시 분기해야 한다. 이 구분을 놓치면 판은 끝났는데 차례
 * 표시만 살아 있는 상태가 된다.
 */
export type OmokMovedPayload = OmokMovePlaced | OmokMoveWon

/** OmokGame 이 돌려주는 거부 사유. 렌주 금수 판정은 RenjuRule 의 verdict 이름이 그대로 온다. */
export type OmokRejectionReason =
    | "GAME_FINISHED"
    | "NOT_YOUR_TURN"
    | "OUT_OF_BOUNDS"
    | "OCCUPIED"
    | "DOUBLE_THREE"
    | "DOUBLE_FOUR"
    | "OVERLINE"

export interface OmokRejectedPayload {
    reason: OmokRejectionReason | (string & {})
}

export interface DodgePosition {
    participantId: string
    x: number
    y: number
}

export interface DodgeCell {
    x: number
    y: number
}

/** DodgeGameSink.tick — eliminated 는 "이번 틱에 탈락한" participantId 목록이다. */
export interface DodgeTickPayload {
    tick: number
    positions: DodgePosition[]
    obstacles: DodgeCell[]
    eliminated: string[]
}

/**
 * GAME_SNAPSHOT 의 오목 착수 하나. OMOK_MOVED 와 달리 participantId 가 없다
 * (OmokGameSink.onRejoin:200-206 은 x·y·color 만 싣는다) — 판을 다시 세우는 데에는 색이면
 * 충분하고, 누가 뒀는지는 이미 지나간 정보이기 때문이다. 그래서 {@link OmokMoveBase} 를
 * 재사용하지 않는다. 그쪽을 재사용하면 스냅샷의 착수에서 존재하지 않는 participantId 를
 * 읽는 코드가 통과한다.
 */
export interface OmokSnapshotMove {
    x: number
    y: number
    color: OmokMoveBase["color"]
}

/**
 * OmokGameSink.onRejoin — 착수 목록은 완전하고 둔 순서대로다. 클라이언트는 이것을 처음부터
 * 재생해 판을 세운다. <b>기보 형식이 아니다</b>: OmokReplayWriter 는 색을 헤더의 players[] 에
 * 한 번만 선언하고 각 수를 {t, p, x, y} 로 적는다. 스냅샷은 자기 자신을 설명하므로 헤더가
 * 없다 — 두 형식의 파서를 합치려 하지 말 것.
 *
 * <p>싱크는 이미 끝난 판에는 스냅샷을 내지 않는다(`game.finished()` 가드). 그래서 nextTurn 과
 * turnDeadline 은 OMOK_MOVED 의 승리 착수와 달리 언제나 실려 온다.
 */
export interface OmokSnapshotPayload {
    /** Extract 로 {@link GameType} 에 묶어 둔다 — 서버가 이름을 바꾸면 여기서 깨져야 한다. */
    gameType: Extract<GameType, "OMOK">
    moves: OmokSnapshotMove[]
    nextTurn: string
    turnDeadline: string
}

/**
 * DodgeGameSink.onRejoin — 평소 DODGE_TICK 이 싣는 프레임과 같은 내용이되
 * <b>eliminated 가 없다</b>(onRejoin:249-254 는 tick·positions·obstacles 만 싣는다).
 * "이번 틱에 탈락한 사람" 은 그 틱에만 뜻이 있는 증분이라 전체 상태에는 들어갈 자리가 없다.
 * 그래서 {@link DodgeTickPayload} 를 그대로 쓰지 않는다.
 */
export interface DodgeSnapshotPayload {
    gameType: Extract<GameType, "DODGE">
    tick: number
    positions: DodgePosition[]
    obstacles: DodgeCell[]
}

/**
 * RoomCommandDispatcher 가 재접속(ROOM_STATE 직후)에 방 전체로 내보내는 전체 상태.
 * 두 게임이 같은 타입 이름을 쓰고 gameType 으로 갈린다 — 판별 유니온이므로
 * `payload.gameType === "OMOK"` 으로 좁히기 전에는 moves 도 tick 도 읽을 수 없다:
 *
 * ```ts
 * if (isServerMessage(message, "GAME_SNAPSHOT") && message.payload.gameType === "OMOK") {
 *     replaceBoard(message.payload.moves)
 * }
 * ```
 */
export type GameSnapshotPayload = OmokSnapshotPayload | DodgeSnapshotPayload

/**
 * 서버: `com.woobeee.game.ws.payload.ErrorPayload`.
 * RoomCommandDispatcher.guard 가 예외를 흡수해 내보내는 모양이고, 참가 인증에 실패한 세션에는
 * GameWebSocketHandler 가 소켓을 닫기 직전에 같은 모양을 직접 한 프레임 써 준다(그 세션은 아직
 * 방 허브를 구독하지 않아 브로드캐스트가 닿지 않는다). 게임 명령에서 비롯된 실패라면 그 명령의
 * seq 가 ackSeq 로 돌아온다.
 *
 * <p><b>code 는 문자열 키다</b> — `game_roomFull` 처럼. HTTP 실패 응답의 `header.message` 와
 * 같은 값이고, 같은 지도(`lib/errors/error-messages.ts`)로 문구를 찾는다. 숫자 상태는
 * `status` 다. 예전에는 `code` 가 숫자였는데, 두 통로에서 같은 낱말이 다른 것을 가리키면
 * 반드시 사고가 나므로 역할을 HTTP 쪽과 맞췄다.
 *
 * @example
 * if (isServerMessage(message, "ERROR")) {
 *     setBanner(getFriendlyErrorMessage(message.payload.code))
 * }
 */
export interface ErrorPayload {
    /** 문구 지도의 키. `game_*`. */
    code?: string
    /** HTTP 상태에 대응하는 숫자. 알 수 없는 런타임 예외는 500. */
    status?: number
    /** 서버가 로그용으로 붙인 영어 설명. 사용자에게 그대로 보여주지 않는다. */
    message: string
}

/** 서버가 실제로 브로드캐스트하는 메시지 타입 전부. */
export interface ServerMessagePayloads {
    ROOM_STATE: RoomStatePayload
    GAME_START: GameStartPayload
    GAME_END: GameEndPayload
    OMOK_MOVED: OmokMovedPayload
    OMOK_REJECTED: OmokRejectedPayload
    DODGE_TICK: DodgeTickPayload
    /** 재접속한 참가자를 위한 전체 상태. 방 전체가 받는다. */
    GAME_SNAPSHOT: GameSnapshotPayload
    ERROR: ErrorPayload
}

export type ServerMessageType = keyof ServerMessagePayloads

/**
 * 서버: `record ServerMessage(String type, Long ackSeq, Object payload)` + `NON_NULL`.
 * ackSeq 는 ServerMessage.ack(...) 로 보낸 것(OMOK_MOVED, OMOK_REJECTED, ERROR)에만 붙고
 * 나머지에는 아예 필드가 없다.
 *
 * <p>type 은 알려진 타입의 합집합이되 `string` 도 받는다 — 서버가 나중에 새 타입을 추가해도
 * 이 클라이언트가 메시지를 통째로 버리지 않게 하기 위해서다.
 *
 * <p><b>payload 는 unknown 이다.</b> `any` 로 두면 위에 옮겨 적은 페이로드 타입들이 전부
 * 장식이 된다 — `switch (message.type)` 로는 payload 가 좁혀지지 않으므로 승리 착수에
 * 존재하지도 않는 `payload.nextTurn` 을 읽는 코드가 그대로 통과한다. 반드시
 * {@link isServerMessage} 로 좁혀서 읽는다:
 *
 * ```ts
 * if (isServerMessage(message, "ROOM_STATE")) {
 *     setParticipants(message.payload.participants)
 * }
 * ```
 */
export interface ServerMessage {
    type: ServerMessageType | (string & {})
    ackSeq?: number
    payload: unknown
}

/** 알려진 타입 하나로 좁혀진 메시지. payload 가 그 타입의 페이로드로 확정된다. */
export interface TypedServerMessage<T extends ServerMessageType> {
    type: T
    ackSeq?: number
    payload: ServerMessagePayloads[T]
}

/** payload 를 읽는 유일한 정문. */
export function isServerMessage<T extends ServerMessageType>(
    message: ServerMessage,
    type: T
): message is TypedServerMessage<T> {
    return message.type === type
}

// ---------------------------------------------------------------------------
// 소켓
// ---------------------------------------------------------------------------

/**
 * - `connecting`  최초 핸드셰이크 진행 중
 * - `open`        핸드셰이크만 끝났다. JOIN 을 보냈을 뿐 아직 방에 들어간 것이 아니다
 * - `joined`      서버가 참가를 확정했다(첫 ROOM_STATE 도착). 입력을 열어도 되는 유일한 상태
 * - `reconnecting` 백오프 대기 중이거나 재접속 핸드셰이크 진행 중
 * - `rejected`    참가가 확정되기 전에 서버가 정상 종료(1000)로 세션을 닫았다. 종단 상태
 * - `closed`      호출자가 닫았거나, 재시도를 다 썼거나, 참가 이후 서버가 정상 종료했다. 종단 상태
 *
 * `open` 과 `joined` 를 나눈 이유: 핸드셰이크는 언제나 성공하므로 "핸드셰이크됨" 을 "참가됨" 으로
 * 쓰면 화면이 왕복 하나만큼 이르게 입력을 열고, 거절당한 재접속을 성공으로 착각한다. JOIN 확정
 * 신호는 첫 ROOM_STATE 다.
 *
 * <p>`rejected` 에는 이유가 따라온다 — 서버는 참가를 거절할 때 세션을 닫기 직전에 코드가 실린
 * ERROR 프레임을 그 세션에 직접 써 준다(GameWebSocketHandler.rejectWithReason). 토큰 인증
 * 실패와 방의 거절(틀린 초대 코드·정원 초과·이미 시작) 둘 다 그렇다. 그 코드가
 * `onStatusChange` 의 두 번째 인자로 온다.
 */
export type SocketStatus =
    | "connecting"
    | "open"
    | "joined"
    | "reconnecting"
    | "rejected"
    | "closed"

export interface GameSocketOptions {
    roomId: string
    inviteCode: string
    /**
     * 재접속마다 다시 읽는다. 세션 도중 만료되는 액세스 토큰을 쓴다면 함수를 넘겨,
     * 그때그때 갱신된 토큰이 JOIN 에 실리게 한다 — 문자열로 고정하면 만료된 뒤의 모든
     * 재접속이 인증에서 막히고, 그 실패가 곧바로 종단 `rejected` 가 된다.
     */
    token: string | (() => string)
    onMessage: (message: ServerMessage) => void
    /**
     * @param errorCode `rejected` 일 때만, 그리고 서버가 이유를 보내 준 경우에만 채워진다.
     *   `game_*` 문자열이므로 `getFriendlyErrorMessage(errorCode)` 로 문구를 만든다.
     *   프레임이 오기 전에 연결이 끊기면 없을 수 있으니 폴백 문구를 준비해 둘 것.
     */
    onStatusChange?: (status: SocketStatus, errorCode?: string) => void
}

export interface GameSocket {
    send: (type: string, payload?: unknown) => number
    close: () => void
}

export const MAX_RETRIES = 5
export const BASE_DELAY_MS = 500

/** 서버가 참가를 거절할 때 쓰는 종료 코드. Spring 의 `session.close()` 기본값이다. */
export const NORMAL_CLOSURE = 1000

/**
 * 지수 백오프. attempt 는 지금까지 실패한 횟수(0-based)다: 500 · 1000 · 2000 · 4000 · 8000ms,
 * 다섯 번을 다 쓰면 누적 15.5초다. 서버의 이탈 유예(DISCONNECT_GRACE)가 30초이므로 다섯 번째
 * 시도까지는 방의 자리가 아직 남아 있다 — 이 한도를 늘릴 거면 그 30초를 먼저 확인해야 한다.
 *
 * <p>순수 함수로 빼 둔 이유는 나중에 vitest 로 이 계산만 따로 고정하기 위해서다.
 */
export function reconnectDelayMs(attempt: number): number {
    return BASE_DELAY_MS * 2 ** attempt
}

/**
 * 끊긴 소켓을 다시 붙어 볼지 판정한다.
 *
 * <p>핵심은 `code === 1000` 이다. 이 서버에서 WebSocket 핸드셰이크는 언제나 성공한다 —
 * 실패하는 것은 그다음의 JOIN 이고, 서버는 그때 메시지 없이 세션을 정상 종료로 닫는다
 * (토큰 만료, 초대 코드 불일치, 정원 초과, 이미 시작된 게임, 유예가 지나 사라진 방).
 * 그래서 종료 코드를 보지 않으면 "붙는다 → 거절당한다 → 500ms 뒤 다시 붙는다" 가 영원히
 * 반복된다. 정상 종료는 서버가 내린 결정이므로 다시 시도하지 않는다.
 *
 * <p>우리가 직접 부른 `close()` 는 상태 코드 없이 닫히므로(1005) 여기 걸리지 않고,
 * 네트워크 단절은 1006 이다 — 둘 다 재접속 대상이다.
 */
export function shouldReconnect(code: number, attempts: number): boolean {
    return code !== NORMAL_CLOSURE && attempts < MAX_RETRIES
}

/** 소켓 밖에서도 검증할 수 있게 직렬화를 분리해 둔다. */
export function encodeClientMessage(type: string, seq: number, payload?: unknown): string {
    return JSON.stringify({ type, seq, payload: payload ?? {} })
}

/** 서버가 JSON 이 아닌 것을 보내면 null 이다 — 그것 때문에 소켓을 끊을 이유는 없다. */
export function parseServerMessage(raw: unknown): ServerMessage | null {
    if (typeof raw !== "string") {
        return null
    }
    try {
        const parsed = JSON.parse(raw)
        if (parsed === null || typeof parsed !== "object" || typeof parsed.type !== "string") {
            return null
        }
        return parsed as ServerMessage
    } catch {
        return null
    }
}

export function createGameSocket(options: GameSocketOptions): GameSocket {
    let socket: WebSocket | null = null
    let seq = 0
    let retries = 0
    let closedByCaller = false
    let settled = false
    let retryTimer: ReturnType<typeof setTimeout> | undefined

    /** 종단 상태는 한 번만 알린다 — 나중의 close() 가 rejected 를 closed 로 덮어쓰지 않게. */
    const setStatus = (status: SocketStatus, errorCode?: string) => {
        if (settled) {
            return
        }
        if (status === "closed" || status === "rejected") {
            settled = true
        }
        options.onStatusChange?.(status, errorCode)
    }

    const resolveToken = (): string =>
        typeof options.token === "function" ? options.token() : options.token

    /**
     * 버릴 소켓의 핸들러는 반드시 떼어 낸다. 안 그러면 이미 대체된 소켓의 뒤늦은 onclose 가
     * 재접속을 한 번 더 예약해 연결이 둘로 갈라진다.
     */
    const detach = (target: WebSocket | null) => {
        if (!target) {
            return
        }
        target.onopen = null
        target.onmessage = null
        target.onclose = null
        target.onerror = null
    }

    const open = () => {
        setStatus(retries === 0 ? "connecting" : "reconnecting")

        const current = new WebSocket(gameSocketUrl())
        socket = current

        // 이 연결에서 서버가 참가를 확정했는지. 확정 전까지는 재시도 카운터를 되돌리지 않는다.
        let joinConfirmed = false
        // 참가 확정 전에 받은 ERROR 의 코드. 서버가 거절 직전에 보내 주는 그 프레임이다.
        let rejectionCode: string | undefined

        current.onopen = () => {
            // 여기서 retries 를 0 으로 되돌리면 안 된다. 핸드셰이크는 언제나 성공하므로
            // 거절당하는 JOIN 에 대해서도 카운터가 매번 초기화되어 MAX_RETRIES 에 영영
            // 닿지 못한다 — 500ms 마다 영원히 다시 붙는 루프가 된다.
            setStatus("open")

            // 재접속도 JOIN 이다. 서버는 이미 아는 participantId 면 정원·진행 상태 검사를
            // 건너뛰고 연결만 CONNECTED 로 되돌린다(RoomService.join -> Room.admit ->
            // RECONNECTED). 진행 중인 판이었다면 ROOM_STATE 뒤에 GAME_SNAPSHOT 이 이어 온다
            // (RoomCommandDispatcher:65 -> 각 싱크의 onRejoin). 그것이 판/틱을 되돌려 주는
            // 유일한 메시지이므로, 화면은 GAME_SNAPSHOT 을 반드시 처리해야 한다 —
            // 무시하면 재접속한 화면이 끊긴 시점의 낡은 상태로 남는다.
            send("JOIN", {
                roomId: options.roomId,
                inviteCode: options.inviteCode,
                token: resolveToken(),
            })
        }

        current.onmessage = (event) => {
            const message = parseServerMessage(event.data)
            if (!message) {
                return
            }

            // 첫 ROOM_STATE 가 참가 확정 신호다. 이 세션은 dispatcher.join 이 검증을 통과시킨
            // 뒤에야 허브를 구독하므로(GameWebSocketHandler:158-161), ROOM_STATE 가 한 번이라도
            // 도착했다는 것은 서버가 우리를 방에 넣었다는 뜻이다.
            if (!joinConfirmed && isServerMessage(message, "ROOM_STATE")) {
                joinConfirmed = true
                retries = 0
                setStatus("joined")
            }

            // 참가가 확정되기 전의 ERROR 는 거절 사유다. 곧바로 onclose 가 이어지므로
            // 여기서 붙잡아 두었다가 rejected 와 함께 올려 보낸다.
            if (!joinConfirmed && isServerMessage(message, "ERROR")) {
                rejectionCode = message.payload.code
            }

            options.onMessage(message)
        }

        current.onclose = (event) => {
            detach(current)

            if (closedByCaller) {
                setStatus("closed")
                return
            }

            if (!shouldReconnect(event.code, retries)) {
                // 참가가 확정되기 전의 정상 종료는 서버의 거절이다. 이유는 닫히기 직전에
                // 이 세션에 직접 온 ERROR 프레임에 실려 있다(방 허브가 아니라 — 이 세션은
                // 아직 허브를 구독하지 않았다). 그 코드를 상태와 함께 올려 보낸다.
                const rejected = !joinConfirmed && event.code === NORMAL_CLOSURE
                setStatus(rejected ? "rejected" : "closed", rejected ? rejectionCode : undefined)
                return
            }

            const delay = reconnectDelayMs(retries)
            retries += 1
            setStatus("reconnecting")
            retryTimer = setTimeout(open, delay)
        }

        current.onerror = () => {
            // onerror 는 그 자체로 연결을 끝내지 않는다. close() 를 불러 onclose 의
            // 재접속 경로 하나로 합류시킨다.
            current.close()
        }
    }

    /**
     * seq 는 연결이 아니라 이 클라이언트 인스턴스에 대해 단조 증가한다 — 재접속해도 이어진다.
     * 서버는 이 값을 ackSeq 로 되돌려 줄 뿐이므로 재시작할 이유가 없고, 이어 두면 재접속 전후의
     * ack 가 섞이지 않는다.
     *
     * <p>소켓이 열려 있지 않으면 메시지는 그냥 버린다(큐에 쌓지 않는다). 끊긴 동안 눌린 착수나
     * 이동을 나중에 몰아서 보내면 이미 지나간 판·틱에 대한 명령이 되어 더 나쁘다. 화면은
     * onStatusChange 가 `joined` 를 알린 동안에만 입력을 열어야 한다 — `open` 은 아직
     * 참가가 확정되지 않은 상태라 여기서 보낸 명령이 서버에 버려진다.
     */
    const send = (type: string, payload?: unknown): number => {
        seq += 1
        if (socket?.readyState === WebSocket.OPEN) {
            socket.send(encodeClientMessage(type, seq, payload))
        }
        return seq
    }

    open()

    return {
        send,
        close: () => {
            closedByCaller = true
            if (retryTimer !== undefined) {
                clearTimeout(retryTimer)
                retryTimer = undefined
            }

            // 백오프 대기 중이라면 살아 있는 소켓이 없어 onclose 가 다시 불리지 않는다.
            // 그 경우에도 호출자는 종료 상태를 한 번은 받아야 한다.
            const pending = socket
            if (!pending || pending.readyState === WebSocket.CLOSED) {
                detach(pending)
                setStatus("closed")
                return
            }
            pending.close()
        },
    }
}
