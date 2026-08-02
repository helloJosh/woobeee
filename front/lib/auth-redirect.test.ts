import { describe, expect, it } from "vitest"
import {
    HOME_PATH,
    NEXT_PARAM,
    buildAuthHref,
    returnPathFor,
    sanitizeNextPath,
} from "./auth-redirect"

/**
 * `sanitizeNextPath` 는 로그인 복귀 경로의 오픈 리다이렉트 가드다. Task 6C 리뷰가 39개
 * 페이로드로 훑어 새는 곳이 없음을 확인했지만, 그 확인은 보고서 안의 산문이었을 뿐 실행되는
 * 것이 아무것도 없었다 — 조건 한 줄만 지워도 아무도 모르게 다시 열린다. 그래서 그 페이로드를
 * 여기 고정한다.
 *
 * 단언을 두 종류로 나눈 것은 의도적이다.
 *
 * - **거절되어야 하는 것**은 `HOME_PATH` 와 정확히 같아야 한다.
 * - **통과하지만 오리진을 벗어나면 안 되는 것**(퍼센트 인코딩, `/..//` 류)은 반환값 자체가
 *   아니라 *성질*을 고정한다: 그 값을 이 사이트 기준으로 해석했을 때 오리진이 그대로여야 한다.
 *   반환값을 박아 두면 나중에 가드를 더 좁게 조이는 정당한 변경이 테스트를 깨뜨린다 —
 *   여기서 지켜야 하는 것은 "무엇을 돌려주느냐" 가 아니라 "오리진을 벗어날 수 없다" 이다.
 */

const ORIGIN = "https://woobeee.example"

/** 전부 `/` 로 떨어져야 한다. */
const REJECTED: Array<[label: string, payload: string]> = [
    // --- 스킴 ---
    ["absolute https", "https://evil.example"],
    ["absolute http", "http://evil.example"],
    ["javascript:", "javascript:alert(1)"],
    ["mixed-case javascript:", "JaVaScRiPt:alert(1)"],
    ["data:", "data:text/html,<script>alert(1)</script>"],
    ["vbscript:", "vbscript:msgbox(1)"],
    ["file:", "file:///etc/passwd"],
    ["mailto:", "mailto:a@b.example"],
    ["scheme with a single slash", "https:/evil.example"],
    ["scheme behind leading whitespace", "   https://evil.example"],

    // --- 스킴 상대 URL. 브라우저는 이것을 외부 호스트로 읽는다 ---
    ["bare protocol-relative", "//evil.example"],
    ["protocol-relative with a path", "//evil.example/game/dodge/R1?invite=C1"],
    ["backslash protocol-relative", "/\\evil.example"],
    ["mixed slash-backslash", "/\\/evil.example"],
    ["double backslash", "/\\\\evil.example"],
    ["leading double backslash", "\\\\evil.example"],
    ["protocol-relative behind whitespace", "  //evil.example"],
    ["protocol-relative behind a tab", "\t//evil.example"],

    // --- 제어문자. 브라우저는 URL 안의 이것들을 조용히 빼고 해석하므로
    //     `/<TAB>/evil.example` 이 `//evil.example` 이 된다 ---
    ["tab", "/\t/evil.example"],
    ["newline", "/\n/evil.example"],
    ["carriage return", "/\r/evil.example"],
    ["NUL", "/\u0000/evil.example"],
    ["DEL", "/game\u007F/dodge"],
    ["C1 control", "/game\u009F/dodge"],
    ["vertical tab", "/\u000B/evil.example"],

    // --- 값이 없는 것 ---
    ["empty string", ""],
    ["whitespace only", "    "],
    ["tab only", "\t"],

    // --- 절대 경로가 아닌 것 ---
    ["relative path", "game/dodge/R1"],
    ["dot-relative path", "./game/dodge/R1"],
    ["parent-relative path", "../game/dodge/R1"],
    ["encoded slashes with no leading slash", "%2F%2Fevil.example"],
    ["bare host", "evil.example"],
    ["query only", "?next=/game"],
    ["fragment only", "#top"],
]

/**
 * 가드가 통과시키는 값들. 통과 자체는 옳다 — 퍼센트 인코딩된 슬래시와 `..` 는 URL 해석
 * 단계에서 호스트로 승격되지 않기 때문이다. 여기서 고정하는 것은 바로 그 사실이다.
 */
const STAYS_ON_ORIGIN: Array<[label: string, payload: string]> = [
    ["single-encoded slashes", "/%2F%2Fevil.example"],
    ["double-encoded slashes", "/%252F%252Fevil.example"],
    ["encoded backslashes", "/%5C%5Cevil.example"],
    ["encoded tab", "/%09/evil.example"],
    // `/..//evil.example` 는 점 세그먼트 제거 뒤 경로가 `//evil.example` 가 되지만, 스킴
    // 상대로 승격되지는 않는다 — 호스트는 참조를 *파싱할 때* 정해지고 그때 이미 이 오리진으로
    // 확정돼 있다. 실측: new URL("/..//evil.example", ORIGIN).origin === ORIGIN.
    ["parent-segment normalisation", "/..//evil.example"],
    ["repeated parent segments", "/../..//evil.example"],
    ["current-segment normalisation", "/.//evil.example"],
    ["interior space", "/ /evil.example"],
]

/** 정상 목적지는 한 글자도 바뀌지 않고 그대로 나와야 한다. */
const PASSED_THROUGH: string[] = [
    "/",
    "/game",
    "/game/dodge/R1?invite=C1",
    "/game/omok/R1#top",
    "/blog/posts/1?page=2&size=10",
]

describe("sanitizeNextPath", () => {
    it.each(REJECTED)("rejects %s", (_label, payload) => {
        expect(sanitizeNextPath(payload)).toBe(HOME_PATH)
    })

    it.each(STAYS_ON_ORIGIN)("cannot leave the origin via %s", (_label, payload) => {
        expect(new URL(sanitizeNextPath(payload), ORIGIN).origin).toBe(ORIGIN)
    })

    it.each(PASSED_THROUGH)("passes %s through unchanged", (payload) => {
        expect(sanitizeNextPath(payload)).toBe(payload)
    })

    it("treats non-strings as no destination", () => {
        expect(sanitizeNextPath(null)).toBe(HOME_PATH)
        expect(sanitizeNextPath(undefined)).toBe(HOME_PATH)
        expect(sanitizeNextPath(42 as unknown as string)).toBe(HOME_PATH)
        expect(sanitizeNextPath({} as unknown as string)).toBe(HOME_PATH)
    })

    it("is idempotent — sanitizing its own output changes nothing", () => {
        for (const [, payload] of [...REJECTED, ...STAYS_ON_ORIGIN]) {
            const once = sanitizeNextPath(payload)
            expect(sanitizeNextPath(once)).toBe(once)
        }
    })
})

/**
 * 가드의 유일한 배출구. 여기서 다시 걸러 주지 않으면 오염된 `next` 가 로그인·회원가입
 * 상호 링크에 그대로 실려 나간다.
 */
describe("buildAuthHref", () => {
    it("omits the parameter when the destination is home", () => {
        expect(buildAuthHref("/login", "/")).toBe("/login")
        expect(buildAuthHref("/login", null)).toBe("/login")
    })

    it("encodes a legitimate destination", () => {
        expect(buildAuthHref("/login", "/game/dodge/R1?invite=C1")).toBe(
            `/login?${NEXT_PARAM}=%2Fgame%2Fdodge%2FR1%3Finvite%3DC1`
        )
    })

    it("never re-emits a rejected destination", () => {
        expect(buildAuthHref("/login", "//evil.example")).toBe("/login")
        expect(buildAuthHref("/signup", "javascript:alert(1)")).toBe("/signup")
    })
})

describe("returnPathFor", () => {
    it("carries the current path and query", () => {
        expect(returnPathFor("/game/omok/R1", "invite=C1")).toBe("/game/omok/R1?invite=C1")
        expect(returnPathFor("/game/omok/R1", "?invite=C1")).toBe("/game/omok/R1?invite=C1")
    })

    it("refuses to point back at an auth screen", () => {
        expect(returnPathFor("/login")).toBeNull()
        expect(returnPathFor("/signup")).toBeNull()
        expect(returnPathFor("/logout")).toBeNull()
        expect(returnPathFor("/auth/google/callback", "code=x&state=y")).toBeNull()
    })

    it("returns null when there is nothing worth carrying", () => {
        expect(returnPathFor("/")).toBeNull()
        expect(returnPathFor(null)).toBeNull()
        expect(returnPathFor("not-a-path")).toBeNull()
    })

    it("does not smuggle a foreign origin through the query", () => {
        expect(returnPathFor("/game", "next=https://evil.example")).toBe(
            "/game?next=https://evil.example"
        )
        // 위 값은 여전히 같은 오리진의 경로다 — 쿼리 안의 문자열일 뿐이다.
        expect(new URL(returnPathFor("/game", "next=https://evil.example")!, ORIGIN).origin).toBe(
            ORIGIN
        )
    })
})
