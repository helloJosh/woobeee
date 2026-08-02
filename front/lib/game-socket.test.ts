import { describe, expect, it } from "vitest"
import {
    BASE_DELAY_MS,
    MAX_RETRIES,
    NORMAL_CLOSURE,
    encodeClientMessage,
    isServerMessage,
    parseServerMessage,
    reconnectDelayMs,
    shouldReconnect,
    type DodgeSnapshotPayload,
    type OmokSnapshotPayload,
    type ServerMessage,
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
