import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { gameAPI } from "./api"

/**
 * `apiRequest` 의 401 처리 하나만 본다 — 그 경로가 <b>플레이 중인 판을 날릴 수 있는</b>
 * 유일한 클라이언트 코드이기 때문이다.
 *
 * <p>기본 동작은 사용자가 직접 누른 요청에 맞춰져 있다: 갱신이 실패하면 토큰을 전부 지우고
 * `alert("인증만료되었습니다…")` 를 띄운 뒤 `window.location.reload()` 한다. `suppressAlert`
 * 는 이 alert 를 덮지 않는다 — 그건 4xx 본문에서 만든 안내에만 걸린다.
 *
 * <p>문제는 <b>배경 호출</b>이다. 방에 게스트 토큰으로 들어와 두고 있는 회원(decideJoinGate
 * 가 게스트 토큰을 우선한다)의 액세스 토큰이 만료돼 있으면, 웹소켓 판은 멀쩡한데
 * `gameAPI.me()` 하나가 alert 와 새로고침으로 그 판을 끝낸다. 그래서 그 호출만
 * `suppressUnauthorizedHandler` 로 전역 처리를 끈다.
 *
 * <p>node 환경이라 DOM 이 없다. 필요한 최소한(localStorage · alert · location.reload)만
 * 직접 세운다 — jsdom 을 끌어오는 것보다 무엇을 가정하는지가 눈에 보인다.
 */

const alertSpy = vi.fn()
const reloadSpy = vi.fn()
let store: Map<string, string>

function seedBrowser() {
    store = new Map([
        ["accessToken", "stale-access"],
        ["refreshToken", "stale-refresh"],
    ])

    const storage = {
        getItem: (key: string) => store.get(key) ?? null,
        setItem: (key: string, value: string) => {
            store.set(key, value)
        },
        removeItem: (key: string) => {
            store.delete(key)
        },
    }

    ;(globalThis as any).localStorage = storage
    // handleUnauthorized 는 `window.alert` 가 아니라 맨 `alert` 를 부른다. 전역에 두지
    // 않으면 ReferenceError 로 죽어 버려, 이 테스트가 "alert 를 안 띄웠다" 를 잘못
    // 확인하게 된다 — 실제로 처음 작성했을 때 그 함정에 걸렸다.
    ;(globalThis as any).alert = alertSpy
    ;(globalThis as any).window = {
        localStorage: storage,
        alert: alertSpy,
        location: { reload: reloadSpy },
        crypto: { randomUUID: () => "test-device" },
    }
}

/** 액세스 토큰도 리프레시 토큰도 죽어 있는 상태 — 갱신까지 실패하는 최악의 경로다. */
function everythingIs401() {
    ;(globalThis as any).fetch = vi.fn(async (url: string) => ({
        ok: false,
        status: 401,
        json: async () =>
            String(url).includes("refresh-tokens")
                ? {}
                : { header: { successful: false, message: "auth_expired" } },
    }))
}

beforeEach(() => {
    alertSpy.mockClear()
    reloadSpy.mockClear()
    vi.spyOn(console, "error").mockImplementation(() => {})
    seedBrowser()
    everythingIs401()
})

afterEach(() => {
    vi.restoreAllMocks()
    delete (globalThis as any).window
    delete (globalThis as any).alert
    delete (globalThis as any).localStorage
    delete (globalThis as any).fetch
})

describe("401 handling", () => {
    it("never lets the background identity check end the session or reload the page", async () => {
        await expect(gameAPI.me()).rejects.toThrow()

        expect(alertSpy).not.toHaveBeenCalled()
        expect(reloadSpy).not.toHaveBeenCalled()
        // 토큰을 지우지 않는다. 지우면 게스트 토큰으로 두고 있던 판까지 함께 무너진다.
        expect(store.get("accessToken")).toBe("stale-access")
        expect(store.get("refreshToken")).toBe("stale-refresh")
    })

    /**
     * 대조군. 이 테스트가 없으면 위 테스트는 "401 이 나면 원래 아무 일도 안 한다" 와
     * 구별되지 않는다 — 같은 조건에서 옵트아웃하지 않은 호출은 실제로 세션을 끝낸다.
     */
    it("still ends the session for a call the user asked for", async () => {
        await expect(gameAPI.myResults()).rejects.toThrow()

        expect(alertSpy).toHaveBeenCalled()
        expect(reloadSpy).toHaveBeenCalled()
        expect(store.has("accessToken")).toBe(false)
    })
})
