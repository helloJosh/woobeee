import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import {
    BASE_DELAY_MS,
    MAX_RETRIES,
    NORMAL_CLOSURE,
    createGameSocket,
    encodeClientMessage,
    isServerMessage,
    parseServerMessage,
    reconnectDelayMs,
    shouldReconnect,
    type DodgeSnapshotPayload,
    type OmokSnapshotPayload,
    type ServerMessage,
    type SocketStatus,
} from "./game-socket"

/**
 * Task 3C 의 `__probe-negative.ts` 를 영구화한 것이다. 그 프로브는 여섯 케이스가 모두
 * 컴파일에 실패하는 것을 확인하고 삭제됐고, 그 뒤로 `GAME_SNAPSHOT` 유니온을 지키는
 * 실행 가능한 것은 아무것도 없었다.
 *
 * <p><b>여기서 진짜 검사를 하는 것은 `tsc --noEmit` 이다.</b> `@ts-expect-error` 는 그 줄에
 * 오류가 **반드시 있어야** 통과한다 — 유니온이 느슨해져 그 접근이 합법이 되는 순간 억제할
 * 오류가 없어져 타입 체크가 깨진다. vitest 는 esbuild 로 타입을 벗겨 내므로 런타임에서는
 * 아무것도 확인하지 않는다. 그래서 두 게이트를 다 돌려야 한다:
 * `npx tsc --noEmit` 과 `npm test`.
 */

const OMOK_SNAPSHOT: OmokSnapshotPayload = {
    gameType: "OMOK",
    moves: [{ x: 7, y: 7, color: "BLACK" }],
    nextTurn: "m:11",
    turnDeadline: "2026-08-01T00:00:60Z",
}

const DODGE_SNAPSHOT: DodgeSnapshotPayload = {
    gameType: "DODGE",
    tick: 12,
    positions: [{ participantId: "m:11", x: 3, y: 15 }],
    obstacles: [{ x: 0, y: 0 }],
}

/**
 * 좁히기 전에는 두 게임 어느 쪽 필드도 읽을 수 없고, 좁힌 뒤에는 그 게임의 것만 읽을 수 있다.
 * 이 함수는 호출되기 위해서가 아니라 컴파일되기 위해 존재한다.
 */
function pinTheSnapshotUnion(message: ServerMessage): void {
    if (!isServerMessage(message, "GAME_SNAPSHOT")) {
        return
    }
    const payload = message.payload

    // @ts-expect-error 좁히기 전의 GAME_SNAPSHOT 에서 오목의 moves 를 읽을 수 없다.
    void payload.moves
    // @ts-expect-error 좁히기 전의 GAME_SNAPSHOT 에서 장애물피하기의 tick 을 읽을 수 없다.
    void payload.tick

    if (payload.gameType === "OMOK") {
        void payload.moves
        void payload.nextTurn
        void payload.turnDeadline
        // @ts-expect-error 스냅샷의 착수에는 participantId 가 없다(OmokGameSink.onRejoin).
        void payload.moves[0].participantId
        // @ts-expect-error 오목 스냅샷에 tick 은 없다.
        void payload.tick
    } else {
        void payload.tick
        void payload.positions
        void payload.obstacles
        // @ts-expect-error 장애물피하기 스냅샷에는 eliminated 가 없다(DODGE_TICK 에만 있다).
        void payload.eliminated
        // @ts-expect-error 장애물피하기 스냅샷에 moves 는 없다.
        void payload.moves
    }
}

/**
 * `switch (message.type)` 로는 payload 가 좁혀지지 않는다 — 이것이 Task 3 의 잠복 결함이었고,
 * `isServerMessage` 가 유일한 정문인 이유다.
 */
function pinThatSwitchDoesNotNarrow(message: ServerMessage): void {
    switch (message.type) {
        case "GAME_SNAPSHOT":
            // @ts-expect-error payload 는 unknown 이다. isServerMessage 로만 좁혀진다.
            void message.payload.gameType
            break
        default:
            break
    }
}

describe("GAME_SNAPSHOT discriminated union", () => {
    it("compiles only with the narrowing the type demands", () => {
        // 실행 자체가 목적이 아니다 — tsc 가 위 두 함수를 검사하는 것이 목적이고, 여기서는
        // 좁히기가 런타임에서도 실제로 갈라지는지만 확인한다.
        pinTheSnapshotUnion({ type: "GAME_SNAPSHOT", payload: OMOK_SNAPSHOT })
        pinTheSnapshotUnion({ type: "GAME_SNAPSHOT", payload: DODGE_SNAPSHOT })
        pinThatSwitchDoesNotNarrow({ type: "GAME_SNAPSHOT", payload: DODGE_SNAPSHOT })

        const message: ServerMessage = { type: "GAME_SNAPSHOT", payload: OMOK_SNAPSHOT }
        expect(isServerMessage(message, "GAME_SNAPSHOT")).toBe(true)
        expect(isServerMessage(message, "DODGE_TICK")).toBe(false)

        if (isServerMessage(message, "GAME_SNAPSHOT") && message.payload.gameType === "OMOK") {
            expect(message.payload.moves).toHaveLength(1)
        } else {
            throw new Error("the omok snapshot should have narrowed")
        }
    })
})

/**
 * 소켓 모듈의 순수 함수들. 소스 주석이 "나중에 vitest 로 이 계산만 따로 고정하기 위해서다"
 * 라고 적어 둔 바로 그것이다.
 */
describe("socket plumbing", () => {
    it("backs off exponentially and stops inside the server's disconnect grace", () => {
        expect(reconnectDelayMs(0)).toBe(BASE_DELAY_MS)
        expect([0, 1, 2, 3, 4].map(reconnectDelayMs)).toEqual([500, 1000, 2000, 4000, 8000])
        // 다섯 번을 다 쓰면 15.5초. 서버의 DISCONNECT_GRACE 30초 안이다.
        const total = [0, 1, 2, 3, 4].reduce((sum, attempt) => sum + reconnectDelayMs(attempt), 0)
        expect(total).toBeLessThan(30_000)
    })

    it("never retries a normal closure, because that is the server's decision", () => {
        expect(shouldReconnect(NORMAL_CLOSURE, 0)).toBe(false)
        expect(shouldReconnect(1006, 0)).toBe(true)
        expect(shouldReconnect(1005, 0)).toBe(true)
        expect(shouldReconnect(1006, MAX_RETRIES)).toBe(false)
        expect(shouldReconnect(1006, MAX_RETRIES - 1)).toBe(true)
    })

    it("always sends an object payload", () => {
        expect(encodeClientMessage("START", 3)).toBe('{"type":"START","seq":3,"payload":{}}')
        expect(encodeClientMessage("READY", 4, { ready: true })).toBe(
            '{"type":"READY","seq":4,"payload":{"ready":true}}'
        )
    })

    it("drops anything that is not a typed JSON object", () => {
        expect(parseServerMessage('{"type":"ROOM_STATE","payload":{}}')).toEqual({
            type: "ROOM_STATE",
            payload: {},
        })
        expect(parseServerMessage("not json")).toBeNull()
        expect(parseServerMessage("null")).toBeNull()
        expect(parseServerMessage('{"payload":{}}')).toBeNull()
        expect(parseServerMessage('["ROOM_STATE"]')).toBeNull()
        expect(parseServerMessage(42)).toBeNull()
    })
})

// ---------------------------------------------------------------------------
// createGameSocket — I3: 즉시 이탈
// ---------------------------------------------------------------------------

/**
 * 최소한의 WebSocket 대역. jsdom 을 끌어오지 않는 이유는 이 모듈이 브라우저에 기대는 것이
 * `new WebSocket(url)` · `send` · `close` · 네 개의 콜백 · `WebSocket.OPEN` 뿐이기 때문이다.
 */
class FakeWebSocket {
    static readonly CONNECTING = 0
    static readonly OPEN = 1
    static readonly CLOSING = 2
    static readonly CLOSED = 3

    static instances: FakeWebSocket[] = []

    readyState = FakeWebSocket.CONNECTING
    readonly sent: string[] = []
    /** send 순서와 close 순서를 같은 눈금 위에서 보기 위한 기록. */
    readonly log: string[] = []

    onopen: (() => void) | null = null
    onmessage: ((event: { data: string }) => void) | null = null
    onclose: ((event: { code: number }) => void) | null = null
    onerror: (() => void) | null = null

    constructor(readonly url: string) {
        FakeWebSocket.instances.push(this)
    }

    send(data: string): void {
        this.sent.push(data)
        this.log.push(`SEND ${JSON.parse(data).type}`)
    }

    close(): void {
        this.log.push("CLOSE")
        this.readyState = FakeWebSocket.CLOSED
        this.onclose?.({ code: 1005 })
    }

    /** 서버가 핸드셰이크를 받아 준다. 소켓은 여기서 JOIN 을 보낸다. */
    handshake(): void {
        this.readyState = FakeWebSocket.OPEN
        this.onopen?.()
    }

    /** 첫 ROOM_STATE 가 참가 확정 신호다. */
    confirmJoin(): void {
        this.onmessage?.({ data: JSON.stringify({ type: "ROOM_STATE", payload: { participants: [] } }) })
    }

    typesSent(): string[] {
        return this.sent.map((raw) => JSON.parse(raw).type)
    }
}

describe("createGameSocket leave", () => {
    let originalWebSocket: unknown

    beforeEach(() => {
        FakeWebSocket.instances = []
        originalWebSocket = (globalThis as { WebSocket?: unknown }).WebSocket
        ;(globalThis as { WebSocket?: unknown }).WebSocket = FakeWebSocket
        // 재접속 백오프를 실제로 기다리지 않는다. 이 스위트에 실시간 대기는 두지 않는다.
        vi.useFakeTimers()
    })

    afterEach(() => {
        vi.useRealTimers()
        ;(globalThis as { WebSocket?: unknown }).WebSocket = originalWebSocket
    })

    function joinedSocket() {
        const statuses: SocketStatus[] = []
        const socket = createGameSocket({
            roomId: "room-1",
            inviteCode: () => "code",
            token: () => "tok",
            onMessage: () => {},
            onStatusChange: (status) => statuses.push(status),
        })
        const wire = FakeWebSocket.instances[0]
        wire.handshake()
        wire.confirmJoin()
        return { socket, wire, statuses }
    }

    /**
     * I3 (수정 2회차) — <b>종료 방법은 하나뿐이어야 한다.</b>
     *
     * <p>예전에는 `close()` 와 `leave()` 가 둘 다 있었고, 화면이 `close()` 를 부르면 30초
     * 유령 버그가 조용히 되살아났다. 리뷰어가 이펙트 두 개의 순서만 바꿔 그 상태를 재현했고
     * 320개 테스트가 전부 통과했다 — 주석으로만 지키던 불변식이었다.
     *
     * <p>그래서 고를 수 없게 만들었다. 이 테스트는 그 결정 자체를 고정한다: 표면에 `close`
     * 가 다시 생기면 여기서 깨진다.
     */
    it("offers exactly one way to let go of the socket", () => {
        const { socket } = joinedSocket()

        expect(Object.keys(socket).sort()).toEqual(["leave", "send"])
        expect((socket as unknown as Record<string, unknown>).close).toBeUndefined()
    })

    /**
     * GAME-AC-09 의 클라이언트 쪽 절반. 서버는 LEAVE 를 유예 없이 처리하지만
     * (`GameWebSocketHandler` → `dispatcher.leaveNow`), 화면이 그냥 닫기만 하면 그 경로에
     * 닿지 않고 30초짜리 끊김 유예를 탄다.
     */
    it("announces the departure before closing", () => {
        const { socket, wire } = joinedSocket()

        expect(socket.leave()).toBe(true)

        expect(wire.typesSent()).toEqual(["JOIN", "LEAVE"])
        // 순서가 요점이다. 닫은 뒤에 보내면 프레임이 버려진다.
        expect(wire.log).toEqual(["SEND JOIN", "SEND LEAVE", "CLOSE"])
    })

    /**
     * StrictMode 가 이펙트를 두 번 돌리거나, 붙는 도중에 화면을 떠나는 경우. 서버에 아직 우리
     * 자리가 없으므로 보낼 것이 없고, JOIN 전의 메시지는 어차피 서버가 버린다.
     */
    it("says nothing when the server never confirmed the join", () => {
        const socket = createGameSocket({
            roomId: "room-1",
            inviteCode: () => "code",
            token: () => "tok",
            onMessage: () => {},
        })
        const wire = FakeWebSocket.instances[0]
        wire.handshake()

        expect(socket.leave()).toBe(false)
        expect(wire.typesSent()).toEqual(["JOIN"])
    })

    it("says nothing when the handshake never completed", () => {
        const socket = createGameSocket({
            roomId: "room-1",
            inviteCode: () => "code",
            token: () => "tok",
            onMessage: () => {},
        })

        expect(socket.leave()).toBe(false)
        expect(FakeWebSocket.instances[0].typesSent()).toEqual([])
    })

    /**
     * C1 이 죽은 회원 토큰을 버릴 때 소켓을 놓는 경로. 그 소켓은 종단 `rejected` 이므로
     * 참가가 확정된 적이 없고, 따라서 LEAVE 가 나가면 안 된다.
     *
     * <p>이 성질이 화면 쪽 구조를 떠받친다. 소켓 이펙트의 정리 함수 하나가 <b>모든</b>
     * 해제를 담당할 수 있는 것은, 토큰이 바뀌어 도는 해제가 이렇게 조용하기 때문이다.
     * 여기가 깨지면 게이트로 돌아가는 것만으로 방에서 이탈 처리가 된다.
     */
    it("says nothing when releasing a socket the server rejected", () => {
        const statuses: SocketStatus[] = []
        const socket = createGameSocket({
            roomId: "room-1",
            inviteCode: () => "code",
            token: () => "dead-token",
            onMessage: () => {},
            onStatusChange: (status) => statuses.push(status),
        })
        const wire = FakeWebSocket.instances[0]
        wire.handshake()
        // 서버가 거절 직전에 이유를 써 주고 정상 종료(1000)로 닫는다.
        wire.onmessage?.({
            data: JSON.stringify({ type: "ERROR", payload: { code: "game_invalidGameToken" } }),
        })
        wire.readyState = FakeWebSocket.CLOSED
        wire.onclose?.({ code: NORMAL_CLOSURE })

        expect(statuses).toContain("rejected")
        expect(socket.leave()).toBe(false)
        expect(wire.typesSent()).toEqual(["JOIN"])
    })

    /**
     * I3 — 초대 코드도 JOIN 마다 다시 읽는다. 화면이 이것을 공급자로 넘기는 덕분에 초대
     * 코드가 바뀌어도 소켓을 다시 만들 필요가 없고, 그래서 "이 소켓을 놓는다" 는 사건이
     * 정말 떠날 때만 일어난다.
     */
    it("asks the invite-code supplier again on every JOIN", () => {
        const codes = ["first", "second"]
        createGameSocket({
            roomId: "room-1",
            inviteCode: () => codes.shift() ?? "exhausted",
            token: () => "tok",
            onMessage: () => {},
        })

        const first = FakeWebSocket.instances[0]
        first.handshake()
        first.readyState = FakeWebSocket.CLOSED
        first.onclose?.({ code: 1006 })

        vi.advanceTimersByTime(BASE_DELAY_MS)
        const second = FakeWebSocket.instances[1]
        second.handshake()

        expect(JSON.parse(first.sent[0]).payload.inviteCode).toBe("first")
        expect(JSON.parse(second.sent[0]).payload.inviteCode).toBe("second")
    })

    it("settles the caller on closed exactly once", () => {
        const { socket, statuses } = joinedSocket()

        socket.leave()
        socket.leave()

        expect(statuses).toEqual(["connecting", "open", "joined", "closed"])
    })

    /**
     * I7 의 배선 쪽 절반. JOIN 마다 공급자를 다시 부르지 않으면, 게이트가 넘긴 토큰이
     * 만료된 뒤의 모든 재접속이 인증에서 막힌다.
     */
    it("asks the token supplier again on every JOIN", () => {
        const tokens = ["first", "second"]
        createGameSocket({
            roomId: "room-1",
            inviteCode: () => "code",
            token: () => tokens.shift() ?? "exhausted",
            onMessage: () => {},
        })

        const first = FakeWebSocket.instances[0]
        first.handshake()
        // 네트워크가 끊겼다(1006) — 재접속 대상이다.
        first.readyState = FakeWebSocket.CLOSED
        first.onclose?.({ code: 1006 })

        vi.advanceTimersByTime(BASE_DELAY_MS)
        const second = FakeWebSocket.instances[1]
        second.handshake()

        expect(JSON.parse(first.sent[0]).payload.token).toBe("first")
        expect(JSON.parse(second.sent[0]).payload.token).toBe("second")
    })
})
