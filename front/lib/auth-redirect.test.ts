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
 * - **통과해야 하는 것**은 반환값을 그대로 고정한다. 여기에 "오리진을 벗어나지 않는다" 같은
 *   성질만 적으면 반증 불가능한 테스트가 된다 — 자세한 사정은 ACCEPTED_ENCODED 위의 주석에
 *   적어 두었다.
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
    // 아래 둘은 trim 과 무관하게 "`/` 로 시작해야 한다" 규칙에 걸린다. trim 이 실제로
    // 하는 일은 ACCEPTED_AFTER_TRIM 에서 따로 고정한다 — 여기에 두고 trim 을 검증한다고
    // 적으면 trim 을 지워도 초록인 채로 남는다.
    ["scheme behind leading whitespace", "   https://evil.example"],

    // --- 스킴 상대 URL. 브라우저는 이것을 외부 호스트로 읽는다 ---
    ["bare protocol-relative", "//evil.example"],
    ["protocol-relative with a path", "//evil.example/game/dodge/R1?invite=C1"],
    ["backslash protocol-relative", "/\\evil.example"],
    ["mixed slash-backslash", "/\\/evil.example"],
    ["double backslash", "/\\\\evil.example"],
    ["leading double backslash", "\\\\evil.example"],
    // 이 둘은 trim 이 없으면 `/` 로 시작하지 않아 어차피 걸린다. trim 이 하는 일은
    // "공백을 벗겨 낸 뒤에도 여전히 위험한가" 를 보게 만드는 것이다.
    ["protocol-relative behind whitespace", "  //evil.example"],
    ["protocol-relative behind a tab", "\t//evil.example"],

    // --- 점 세그먼트를 지우고 나면 스킴 상대가 되는 것. 앞의 네 문자열 규칙을 전부
    //     통과하므로 정규화한 경로를 직접 봐야만 걸린다 ---
    ["parent-segment normalisation", "/..//evil.example"],
    ["repeated parent segments", "/../..//evil.example"],
    ["current-segment normalisation", "/.//evil.example"],
    ["parent segments with a query", "/..//evil.example?next=/"],

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
 * 퍼센트 인코딩된 위험 문자들. 이것들은 **통과하는 것이 옳다** — `%2F` 는 URL 해석 단계에서
 * 디코드되지 않으므로 호스트로 승격될 수 없다.
 *
 * <p>여기서 단언을 **반환값 자체**로 잡는 것이 핵심이다. 처음에는 "이 오리진을 벗어나지
 * 않는다" 로 썼는데, 그 형태는 반증 불가능했다: 이 페이로드들에 대해 함수가 낼 수 있는 값은
 * `"/"` 아니면 페이로드 자신뿐이고 둘 다 같은 오리진으로 풀리므로, 가드를 통째로 들어내도
 * 이 블록은 초록으로 남는다(리뷰가 네 규칙을 다 지우고 확인했다 — 실패 36건 중 이 블록은
 * 0건이었다). 반환값을 고정하면 가드의 어떤 변화든 여기서 드러난다.
 */
const ACCEPTED_ENCODED: Array<[label: string, payload: string]> = [
    ["single-encoded slashes", "/%2F%2Fevil.example"],
    ["double-encoded slashes", "/%252F%252Fevil.example"],
    ["encoded backslashes", "/%5C%5Cevil.example"],
    ["encoded tab", "/%09/evil.example"],
    ["interior space", "/ /evil.example"],
    ["dot segments that fold back onto a normal path", "/game/../blog/posts/1"],
]

/** trim 이 실제로 하는 일. 이 셋이 없으면 `.trim()` 을 지워도 아무 테스트도 깨지지 않는다. */
const ACCEPTED_AFTER_TRIM: Array<[payload: string, expected: string]> = [
    ["  /game/dodge/R1  ", "/game/dodge/R1"],
    ["\t/game", "/game"],
    ["/blog\n", "/blog"],
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

    it.each(ACCEPTED_ENCODED)("accepts %s unchanged, and it stays on this origin", (_label, payload) => {
        // 두 단언은 서로 다른 것을 지킨다. 첫째는 가드의 판정 자체(반증 가능), 둘째는 그
        // 판정이 옳다는 근거 — 정규화한 **경로**가 여전히 이 오리진의 경로다.
        expect(sanitizeNextPath(payload)).toBe(payload)
        expect(new URL(new URL(payload, ORIGIN).pathname, ORIGIN).origin).toBe(ORIGIN)
    })

    it.each(ACCEPTED_AFTER_TRIM)("trims %j down to %j", (payload, expected) => {
        expect(sanitizeNextPath(payload)).toBe(expected)
    })

    it.each(PASSED_THROUGH)("passes %s through unchanged", (payload) => {
        expect(sanitizeNextPath(payload)).toBe(payload)
    })

    /**
     * 거절 목록의 마지막 넷(`/..//…`)이 왜 거절인지의 근거. 참조를 통째로 해석하면 오리진이
     * 유지되지만, **정규화한 경로만** 떼어 보면 스킴 상대 URL 이 된다 — 값을 먼저 정규화하고
     * 나중에 이동에 쓰는 소비자(Next App Router 가 그렇다)가 밟는 것이 이 경로다.
     */
    it("would have been a same-origin answer to the wrong question", () => {
        const payload = "/..//evil.example"

        // 참조 전체를 해석하면 오리진은 그대로다 — 그래서 "오리진이 유지되는가" 만 묻는
        // 테스트는 이 값을 위험하다고 말해 주지 못했다.
        expect(new URL(payload, ORIGIN).origin).toBe(ORIGIN)
        // 그런데 정규화된 경로는 `//evil.example` 이고, 그것만 놓고 해석하면 남의 오리진이다.
        expect(new URL(payload, ORIGIN).pathname).toBe("//evil.example")
        expect(new URL(new URL(payload, ORIGIN).pathname, ORIGIN).origin).not.toBe(ORIGIN)
        // 그래서 가드는 이 값을 통과시키지 않는다.
        expect(sanitizeNextPath(payload)).toBe(HOME_PATH)
    })

    it("treats non-strings as no destination", () => {
        expect(sanitizeNextPath(null)).toBe(HOME_PATH)
        expect(sanitizeNextPath(undefined)).toBe(HOME_PATH)
        expect(sanitizeNextPath(42 as unknown as string)).toBe(HOME_PATH)
        expect(sanitizeNextPath({} as unknown as string)).toBe(HOME_PATH)
    })

    it("is idempotent — sanitizing its own output changes nothing", () => {
        for (const [, payload] of [...REJECTED, ...ACCEPTED_ENCODED]) {
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
