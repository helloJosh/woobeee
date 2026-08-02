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

/**
 * OmokGameSink.onGameCommand. 착수 성공(PLACED)에는 nextTurn·turnDeadline 이 실리지만,
 * 승리 착수(WIN)의 OMOK_MOVED 에는 participantId·x·y·color 만 실린다 — 그래서 뒤의 두 필드는
 * 선택이다. turnDeadline 은 Instant.toString() 의 ISO-8601 문자열이다.
 */
export interface OmokMovedPayload {
    participantId: string
    x: number
    y: number
    color: "BLACK" | "WHITE"
    nextTurn?: string
    turnDeadline?: string
}

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
 * RoomCommandDispatcher.guard 가 예외를 흡수해 내보내는 모양. code 는 HTTP 상태 코드이고
 * (알 수 없는 런타임 예외는 500), 이 메시지에만 ackSeq 가 붙을 수 있다 — 게임 명령에서 비롯된
 * 실패라면 그 명령의 seq 가 돌아온다.
 */
export interface ErrorPayload {
    code: number
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
    ERROR: ErrorPayload
}

export type ServerMessageType = keyof ServerMessagePayloads

/**
 * 서버: `record ServerMessage(String type, Long ackSeq, Object payload)` + `NON_NULL`.
 * ackSeq 는 ServerMessage.ack(...) 로 보낸 것(OMOK_MOVED, OMOK_REJECTED, ERROR)에만 붙고
 * 나머지에는 아예 필드가 없다.
 *
 * <p>type 은 알려진 타입의 합집합이되 `string` 도 받는다 — 서버가 나중에 새 타입을 추가해도
 * 이 클라이언트가 메시지를 버리지 않게 하기 위해서다. payload 를 좁히려면 아래
 * {@link isServerMessage} 를 쓴다.
 */
export interface ServerMessage<T = any> {
    type: ServerMessageType | (string & {})
    ackSeq?: number
    payload?: T
}

/** `if (isServerMessage(message, "ROOM_STATE")) { message.payload.participants }` */
export function isServerMessage<T extends ServerMessageType>(
    message: ServerMessage,
    type: T
): message is ServerMessage<ServerMessagePayloads[T]> & { type: T } {
    return message.type === type
}

// ---------------------------------------------------------------------------
// 소켓
// ---------------------------------------------------------------------------

export type SocketStatus = "connecting" | "open" | "reconnecting" | "closed"

export interface GameSocketOptions {
    roomId: string
    inviteCode: string
    token: string
    onMessage: (message: ServerMessage) => void
    onStatusChange?: (status: SocketStatus) => void
}

export interface GameSocket {
    send: (type: string, payload?: unknown) => number
    close: () => void
}

export const MAX_RETRIES = 5
export const BASE_DELAY_MS = 500

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
    let retryTimer: ReturnType<typeof setTimeout> | undefined

    const setStatus = (status: SocketStatus) => options.onStatusChange?.(status)

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

        current.onopen = () => {
            retries = 0
            setStatus("open")
            // 재접속도 JOIN 이다. 서버는 이미 아는 participantId 면 자리를 잇는다
            // (RoomService.join -> Room.admit -> RECONNECTED).
            send("JOIN", {
                roomId: options.roomId,
                inviteCode: options.inviteCode,
                token: options.token,
            })
        }

        current.onmessage = (event) => {
            const message = parseServerMessage(event.data)
            if (message) {
                options.onMessage(message)
            }
        }

        current.onclose = () => {
            detach(current)

            if (closedByCaller || retries >= MAX_RETRIES) {
                setStatus("closed")
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
     * 이동을 나중에 몰아서 보내면 이미 지나간 판·틱에 대한 명령이 되어 더 나쁘다. 호출자는
     * onStatusChange 로 연결 상태를 보고 입력을 막을 수 있다.
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
