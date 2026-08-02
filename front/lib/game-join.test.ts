import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import type { GameType, RoomStatus, RoomSummary } from "@/lib/types"

/**
 * Task 6 이 실제로 돌렸지만 커밋하지 못한 40개의 단언을 옮긴 것이다. 그때는 러너가 없어
 * `tsc` 로 컴파일한 모듈을 node 에서 직접 돌렸고, `vitest` 를 import 하는 파일을 두면
 * 유일한 게이트였던 `tsc --noEmit` 이 깨졌다. 이제 러너가 있으므로 원래 있어야 할 곳에 둔다.
 *
 * 다섯 묶음이다: checkNickname(12) · describeJoinBlock(4) · 게스트 토큰 저장(8) ·
 * decideJoinGate(14) · joinRoomAsGuest(3). 저장 묶음만 Task 6 의 7개보다 하나 많다 —
 * 그 사이에 participantId 가 생겼고, "옛 형식 항목은 토큰만이라도 계속 쓴다" 는 의도된
 * 동작을 고정하는 것이 없었다.
 */

const api = vi.hoisted(() => ({
    getRoomSummary: vi.fn(),
    issueGuestToken: vi.fn(),
}))

vi.mock("@/lib/api", () => ({ gameAPI: api }))

import {
    NICKNAME_MAX_LENGTH,
    checkNickname,
    clearStoredGuestToken,
    decideJoinGate,
    describeJoinBlock,
    discardGuestTokenOnRejection,
    joinRoomAsGuest,
    readStoredGuestIdentity,
    readStoredGuestToken,
    storeGuestToken,
    type JoinGateInput,
} from "./game-join"
import type { SocketStatus } from "./game-socket"

// ---------------------------------------------------------------------------
// sessionStorage 스텁. jsdom 을 끌어오지 않는 이유는 이 모듈이 브라우저에 기대는 것이
// 정확히 이것 하나뿐이기 때문이다 — 무엇을 가정하는지가 여기 다 보인다.
// ---------------------------------------------------------------------------

class MemoryStorage {
    private entries = new Map<string, string>()

    get length(): number {
        return this.entries.size
    }

    key(index: number): string | null {
        return Array.from(this.entries.keys())[index] ?? null
    }

    getItem(key: string): string | null {
        return this.entries.get(key) ?? null
    }

    setItem(key: string, value: string): void {
        this.entries.set(key, String(value))
    }

    removeItem(key: string): void {
        this.entries.delete(key)
    }

    clear(): void {
        this.entries.clear()
    }
}

let storage: MemoryStorage

beforeEach(() => {
    storage = new MemoryStorage()
    ;(globalThis as { window?: unknown }).window = { sessionStorage: storage }
    api.getRoomSummary.mockReset()
    api.issueGuestToken.mockReset()
    vi.spyOn(console, "error").mockImplementation(() => {})
})

afterEach(() => {
    delete (globalThis as { window?: unknown }).window
    vi.useRealTimers()
    vi.restoreAllMocks()
})

// ---------------------------------------------------------------------------
// 1. checkNickname — 서버 NicknameValidator 와의 대조 (12)
// ---------------------------------------------------------------------------

describe("checkNickname", () => {
    it("trims and accepts", () => {
        expect(checkNickname("  woobeee  ")).toEqual({ ok: true, value: "woobeee" })
    })

    it("accepts exactly 20 characters", () => {
        const name = "a".repeat(NICKNAME_MAX_LENGTH)
        expect(checkNickname(name)).toEqual({ ok: true, value: name })
    })

    it("rejects 21 characters", () => {
        expect(checkNickname("a".repeat(NICKNAME_MAX_LENGTH + 1)).ok).toBe(false)
    })

    it("rejects a name that is 20 characters only before trimming", () => {
        // 자바 NicknameValidator 도 trim 후에 길이를 잰다.
        expect(checkNickname(`  ${"a".repeat(NICKNAME_MAX_LENGTH)}  `)).toEqual({
            ok: true,
            value: "a".repeat(NICKNAME_MAX_LENGTH),
        })
    })

    it("rejects an empty name", () => {
        expect(checkNickname("").ok).toBe(false)
    })

    it("rejects a whitespace-only name", () => {
        expect(checkNickname("    ").ok).toBe(false)
    })

    it("rejects an embedded control character", () => {
        expect(checkNickname("wo\u0007beee").ok).toBe(false)
    })

    it("rejects U+0000, the low boundary of Character.isISOControl", () => {
        expect(checkNickname("a\u0000b").ok).toBe(false)
    })

    it("rejects U+009F, the high boundary of Character.isISOControl", () => {
        expect(checkNickname("a\u009Fb").ok).toBe(false)
    })

    it("accepts a tilde, which sits just outside the control range", () => {
        expect(checkNickname("a~b")).toEqual({ ok: true, value: "a~b" })
    })

    it("accepts 20 Hangul syllables", () => {
        const name = "가".repeat(NICKNAME_MAX_LENGTH)
        expect(checkNickname(name)).toEqual({ ok: true, value: name })
    })

    // 길이는 UTF-16 코드 단위다 — 자바 String.length() 와 같은 셈법이라 서버와 어긋나지 않는다.
    it("counts emoji as two code units each, matching Java String.length()", () => {
        expect(checkNickname("😀".repeat(10)).ok).toBe(true)
        expect(checkNickname("😀".repeat(11)).ok).toBe(false)
    })
})

// ---------------------------------------------------------------------------
// 2. describeJoinBlock (4)
// ---------------------------------------------------------------------------

function summaryOf(overrides: Partial<RoomSummary> = {}): RoomSummary {
    return {
        gameType: "DODGE",
        status: "WAITING",
        capacity: 8,
        participantCount: 2,
        ...overrides,
    }
}

describe("describeJoinBlock", () => {
    it("does not block a waiting room with space", () => {
        expect(describeJoinBlock(summaryOf())).toBeNull()
    })

    it("blocks a full room", () => {
        expect(describeJoinBlock(summaryOf({ participantCount: 8, capacity: 8 }))?.reason).toBe("full")
    })

    it("blocks an IN_PROGRESS room", () => {
        expect(describeJoinBlock(summaryOf({ status: "IN_PROGRESS" }))?.reason).toBe("started")
    })

    it("blocks a FINISHED room", () => {
        expect(describeJoinBlock(summaryOf({ status: "FINISHED" }))?.reason).toBe("started")
    })
})

// ---------------------------------------------------------------------------
// 3. 게스트 토큰 저장 (7)
// ---------------------------------------------------------------------------

const SIX_HOURS_MS = 6 * 60 * 60 * 1000

describe("guest token storage", () => {
    it("round trips a token and its participantId", () => {
        storeGuestToken("R1", "tok-1", "g:aaa")
        expect(readStoredGuestIdentity("R1")).toEqual({ token: "tok-1", participantId: "g:aaa" })
        expect(readStoredGuestToken("R1")).toBe("tok-1")
    })

    it("keeps rooms apart", () => {
        storeGuestToken("R1", "tok-1", "g:aaa")
        expect(readStoredGuestToken("R2")).toBeNull()
    })

    it("clears one room's entry", () => {
        storeGuestToken("R1", "tok-1", "g:aaa")
        clearStoredGuestToken("R1")
        expect(readStoredGuestToken("R1")).toBeNull()
    })

    /**
     * 두 게임 화면이 소켓 상태 이펙트에서 부르는 문. 조건이 이펙트 안에 있으면 테스트가 닿지
     * 않아, "closed 에서도 지운다" 같은 변경이 아무 테스트도 깨뜨리지 않은 채 들어온다 —
     * 그러면 잠깐 끊긴 게스트가 재접속할 자리를 잃는다.
     */
    it("discards the token only when the server rejected the join", () => {
        storeGuestToken("R1", "tok-1", "g:aaa")

        expect(discardGuestTokenOnRejection("R1", "rejected")).toBe(true)
        expect(readStoredGuestToken("R1")).toBeNull()
    })

    it("keeps the token for every status that is not a rejection", () => {
        const survives: SocketStatus[] = ["connecting", "open", "joined", "reconnecting", "closed"]

        for (const status of survives) {
            storeGuestToken("R1", "tok-1", "g:aaa")
            expect(discardGuestTokenOnRejection("R1", status)).toBe(false)
            expect(readStoredGuestToken("R1")).toBe("tok-1")
        }
    })

    it("only touches the rejected room", () => {
        storeGuestToken("R1", "tok-1", "g:aaa")
        storeGuestToken("R2", "tok-2", "g:bbb")

        discardGuestTokenOnRejection("R1", "rejected")

        expect(readStoredGuestToken("R2")).toBe("tok-2")
    })

    it("expires the entry at exactly the server TTL and removes it", () => {
        vi.useFakeTimers()
        vi.setSystemTime(new Date("2026-08-01T00:00:00Z"))
        storeGuestToken("R1", "tok-1", "g:aaa")

        vi.setSystemTime(new Date("2026-08-01T00:00:00Z").getTime() + SIX_HOURS_MS + 1)

        expect(readStoredGuestToken("R1")).toBeNull()
        expect(storage.getItem("woobeee:game:guest-token:R1")).toBeNull()
    })

    it("keeps an entry that is five hours old", () => {
        vi.useFakeTimers()
        vi.setSystemTime(new Date("2026-08-01T00:00:00Z"))
        storeGuestToken("R1", "tok-1", "g:aaa")

        vi.setSystemTime(new Date("2026-08-01T00:00:00Z").getTime() + 5 * 60 * 60 * 1000)

        expect(readStoredGuestToken("R1")).toBe("tok-1")
    })

    it("drops a corrupt entry", () => {
        storage.setItem("woobeee:game:guest-token:R1", "{not json")
        expect(readStoredGuestToken("R1")).toBeNull()
        expect(storage.getItem("woobeee:game:guest-token:R1")).toBeNull()
    })

    it("drops a wrong-shaped entry", () => {
        storage.setItem("woobeee:game:guest-token:R1", JSON.stringify({ issuedAt: Date.now() }))
        expect(readStoredGuestToken("R1")).toBeNull()
        expect(storage.getItem("woobeee:game:guest-token:R1")).toBeNull()
    })

    // 오래된 항목에는 participantId 가 없다. 그래도 토큰은 계속 쓸 수 있어야 한다 —
    // 버리면 그 게스트가 새 닉네임으로 두 번째 자리를 차지한다.
    it("keeps a legacy entry that has no participantId", () => {
        storage.setItem(
            "woobeee:game:guest-token:R1",
            JSON.stringify({ token: "tok-old", issuedAt: Date.now() })
        )
        expect(readStoredGuestIdentity("R1")).toEqual({ token: "tok-old", participantId: null })
    })
})

// ---------------------------------------------------------------------------
// 4. decideJoinGate (14)
// ---------------------------------------------------------------------------

function gateInput(overrides: Partial<JoinGateInput> = {}): JoinGateInput {
    return {
        roomId: "R1",
        loadedRoom: "R1",
        authLoading: false,
        isAuthenticated: false,
        memberToken: null,
        storedGuestToken: null,
        summary: summaryOf(),
        expectedGameType: "DODGE" as GameType,
        error: null,
        ...overrides,
    }
}

describe("decideJoinGate", () => {
    it("waits while auth is resolving", () => {
        expect(decideJoinGate(gateInput({ authLoading: true }))).toEqual({ kind: "loading" })
    })

    it("waits while the summary is missing", () => {
        expect(decideJoinGate(gateInput({ summary: null }))).toEqual({ kind: "loading" })
    })

    it("shows the load error", () => {
        expect(decideJoinGate(gateInput({ error: "없는 방입니다." }))).toEqual({
            kind: "error",
            message: "없는 방입니다.",
        })
    })

    it("reports a room whose game type is not the one this screen draws", () => {
        expect(decideJoinGate(gateInput({ expectedGameType: "OMOK" }))).toEqual({ kind: "wrong-game" })
    })

    it("hands over the member token", () => {
        expect(decideJoinGate(gateInput({ isAuthenticated: true, memberToken: "m-tok" }))).toEqual({
            kind: "ready",
            token: "m-tok",
            source: "member",
        })
    })

    it("hands over a stored guest token", () => {
        expect(decideJoinGate(gateInput({ storedGuestToken: "g-tok" }))).toEqual({
            kind: "ready",
            token: "g-tok",
            source: "guest-session",
        })
    })

    it("asks a visitor for a nickname", () => {
        expect(decideJoinGate(gateInput())).toEqual({
            kind: "needs-identity",
            summary: summaryOf(),
            block: null,
        })
    })

    it("asks for a nickname when isAuthenticated is true but the token is gone", () => {
        expect(decideJoinGate(gateInput({ isAuthenticated: true, memberToken: null })).kind).toBe(
            "needs-identity"
        )
    })

    it("blocks a new guest from a full room", () => {
        const stage = decideJoinGate(
            gateInput({ summary: summaryOf({ participantCount: 8, capacity: 8 }) })
        )
        expect(stage.kind).toBe("needs-identity")
        expect(stage.kind === "needs-identity" && stage.block?.reason).toBe("full")
    })

    it("blocks a new guest from a started room", () => {
        const stage = decideJoinGate(gateInput({ summary: summaryOf({ status: "IN_PROGRESS" }) }))
        expect(stage.kind === "needs-identity" && stage.block?.reason).toBe("started")
    })

    // 아래 넷은 리뷰 라운드에서 고친 회귀다. 각각이 되돌아오면 여기서 잡힌다.

    it("returns loading on the frame where the loaded room lags the prop", () => {
        expect(decideJoinGate(gateInput({ roomId: "R2", loadedRoom: "R1" }))).toEqual({
            kind: "loading",
        })
    })

    it("never leaks room A's guest token to room B", () => {
        const stage = decideJoinGate(
            gateInput({ roomId: "R2", loadedRoom: "R1", storedGuestToken: "g-tok-for-R1" })
        )
        expect(stage).toEqual({ kind: "loading" })
    })

    it("prefers a stored guest token over the member token", () => {
        expect(
            decideJoinGate(
                gateInput({ isAuthenticated: true, memberToken: "m-tok", storedGuestToken: "g-tok" })
            )
        ).toEqual({ kind: "ready", token: "g-tok", source: "guest-session" })
    })

    it("lets a stored guest token back into a full room, and a member into a started one", () => {
        // 정원·상태 차단은 확실히 새 참가자인 게스트 갈림길에만 적용된다.
        // Room.admit 은 이미 자리를 가진 사람에게 그 검사보다 먼저 RECONNECTED 를 돌려준다.
        expect(
            decideJoinGate(
                gateInput({
                    summary: summaryOf({ participantCount: 8, capacity: 8 }),
                    storedGuestToken: "g-tok",
                })
            ).kind
        ).toBe("ready")
        expect(
            decideJoinGate(
                gateInput({
                    summary: summaryOf({ status: "IN_PROGRESS" as RoomStatus }),
                    isAuthenticated: true,
                    memberToken: "m-tok",
                })
            ).kind
        ).toBe("ready")
    })
})

// ---------------------------------------------------------------------------
// 5. joinRoomAsGuest (3)
// ---------------------------------------------------------------------------

describe("joinRoomAsGuest", () => {
    it("never reaches the network with an invalid nickname", async () => {
        const outcome = await joinRoomAsGuest("R1", "C1", "   ")

        expect(outcome.kind).toBe("error")
        expect(api.issueGuestToken).not.toHaveBeenCalled()
    })

    it("sends the trimmed nickname", async () => {
        api.issueGuestToken.mockResolvedValue({
            token: "tok",
            participantId: "g:aaa",
            displayName: "woobeee",
        })

        await joinRoomAsGuest("R1", "C1", "  woobeee  ")

        expect(api.issueGuestToken).toHaveBeenCalledWith("R1", "C1", "woobeee")
    })

    it("stores the issued token so a refresh keeps the same seat", async () => {
        api.issueGuestToken.mockResolvedValue({
            token: "tok",
            participantId: "g:aaa",
            displayName: "woobeee",
        })

        const outcome = await joinRoomAsGuest("R1", "C1", "woobeee")

        expect(outcome).toEqual({ kind: "token", token: "tok" })
        expect(readStoredGuestIdentity("R1")).toEqual({ token: "tok", participantId: "g:aaa" })
    })
})
