/**
 * 로그인·회원가입을 마친 뒤 "원래 가려던 곳"으로 돌려보내기 위한 로직. game-join.ts / game-hub.ts
 * 와 같은 이유로 React 에 의존하지 않는다 — 여기 있는 판단(특히 sanitizeNextPath)이야말로
 * 조용히 틀린 채로 몇 년을 가는 종류라, 컴포넌트 밖에 두고 테스트로 고정해야 한다.
 *
 * 두 경로가 있다.
 *
 * 1. 같은 탭 안에서 끝나는 경로 — `/login?next=<path>` 의 쿼리 파라미터로 나른다.
 * 2. Google OAuth 처럼 사이트를 완전히 떠났다 돌아오는 경로 — 쿼리 파라미터가 살아남지 못하므로
 *    sessionStorage 에 맡긴다. 다만 "그냥 하나 넣어 두고 돌아와서 꺼낸다" 로는 부족하다.
 *    버려진 항목이 남아 있으면 한참 뒤의 무관한 로그인이 그 목적지로 끌려간다.
 *    그래서 서버가 발급한 OAuth `state` 를 키로 쓴다. state 는 인증 시도마다 새로 만들어지는
 *    불투명한 값(AuthService.tokenGenerator.nextToken)이고 콜백 URL 에 그대로 되돌아오므로,
 *    "이 왕복" 과 "그 목적지" 를 1:1 로 묶어 준다. 다른 시도의 항목은 키가 달라 절대 집히지
 *    않고, 버려진 항목은 TTL 로 스스로 사라진다.
 */

export const NEXT_PARAM = "next"
export const HOME_PATH = "/"

// game-join.ts 의 ISO_CONTROL 과 같은 범위. 브라우저는 URL 안의 \t \n \r 을 조용히 빼고
// 해석하므로("/\t/evil.example" -> "//evil.example"), 제어문자가 섞인 경로는 통째로 버린다.
const ISO_CONTROL = /[\u0000-\u001F\u007F-\u009F]/

/**
 * `next` 는 사용자가 직접 고쳐 쓸 수 있는 URL 에서 온다. 그 값으로 로그인 직후 이동하므로,
 * 여기서 걸러내지 못하면 그대로 오픈 리다이렉트다. 통과 조건은 하나뿐이다:
 * **같은 오리진의 절대 경로**. 즉 `/` 하나로 시작해야 한다.
 *
 * - `https://evil.example` — 스킴이 있다. `/` 로 시작하지 않으므로 거절.
 * - `javascript:alert(1)` — 같은 이유로 거절.
 * - `//evil.example` — 스킴 상대 URL. 브라우저는 이것을 외부 호스트로 읽는다. 거절.
 * - `/\evil.example` — 백슬래시는 브라우저가 `/` 로 정규화하므로 위와 같다. 거절.
 * - `/..//evil.example` — 앞의 네 규칙을 전부 통과하지만 점 세그먼트를 지우고 나면 경로가
 *   `//evil.example` 이 된다. 아래 PROBE 참고.
 *
 * 거절한 것은 전부 홈으로 떨어뜨린다 — 오류를 보여줄 만한 상황이 아니고, 홈은 항상 안전하다.
 */
export function sanitizeNextPath(raw: string | null | undefined): string {
    if (typeof raw !== "string") {
        return HOME_PATH
    }

    const value = raw.trim()
    if (value.length === 0 || ISO_CONTROL.test(value)) {
        return HOME_PATH
    }
    if (!value.startsWith("/")) {
        return HOME_PATH
    }
    if (value.startsWith("//") || value.startsWith("/\\")) {
        return HOME_PATH
    }
    if (!normalisesToASamePathOnThisOrigin(value)) {
        return HOME_PATH
    }

    return value
}

/**
 * 임의의 오리진. 값 자체는 아무 의미가 없고, 상대 참조를 해석해 볼 기준점으로만 쓴다.
 */
const PROBE_ORIGIN = "https://probe.invalid"

/**
 * 위의 네 규칙을 통과하고도 남는 구멍 하나를 막는다: **점 세그먼트를 지운 뒤의 경로**.
 *
 * `/..//evil.example` 은 `/` 하나로 시작하고 `//` 로도 `/\` 로도 시작하지 않으므로 앞의 검사를
 * 전부 통과한다. 그런데 RFC 3986 의 점 세그먼트 제거를 거치면 경로가 `//evil.example` 가 된다.
 *
 * 참조를 통째로 해석하는 한 이것은 새는 구멍이 아니다 — 호스트는 참조를 *파싱하는 시점*에
 * 이미 이 오리진으로 확정되고, 그 뒤의 정규화는 호스트를 바꾸지 못한다
 * (`new URL("/..//evil.example", origin).origin === origin`). 문제는 값을 **먼저 정규화하고
 * 나중에 이동에 쓰는** 소비자다. Next 의 App Router 가 정확히 그렇게 한다: `new URL` 로 풀어
 * `pathname + search + hash` 를 다시 만든 뒤 `history.pushState` 에 넘긴다. 그 시점의 문자열은
 * `//evil.example` 이고, 그것만 놓고 보면 스킴 상대 URL 이다. 브라우저가 교차 오리진
 * `pushState` 를 막으므로 실제로는 외부로 나가는 대신 SecurityError 가 나지만, 어느 쪽이든
 * 여기서 통과시킬 이유가 없는 값이다.
 *
 * 그래서 점 세그먼트를 지운 결과를 직접 본다. `/a/../b` 처럼 `/b` 로 얌전히 접히는 경로는
 * 그대로 통과하고, `//` 로 시작하도록 접히는 것만 걸린다.
 */
function normalisesToASamePathOnThisOrigin(value: string): boolean {
    let resolved: URL
    try {
        resolved = new URL(value, PROBE_ORIGIN)
    } catch {
        return false
    }
    // origin 검사는 위의 문자열 규칙을 한 번 더 받쳐 주는 것이다 — 문자열로 놓친 스킴 상대
    // 표기가 있어도 여기서 오리진이 달라지므로 걸린다.
    return resolved.origin === PROBE_ORIGIN && !resolved.pathname.startsWith("//")
}

/**
 * `/login` · `/signup` 링크에 돌아갈 곳을 붙인다. 목적지가 홈이면 파라미터를 아예 붙이지
 * 않는다 — `?next=/` 는 아무 정보도 없으면서 URL 만 지저분하게 만든다.
 */
export function buildAuthHref(path: string, next: string | null | undefined): string {
    const safe = sanitizeNextPath(next)
    if (safe === HOME_PATH) {
        return path
    }
    return `${path}?${NEXT_PARAM}=${encodeURIComponent(safe)}`
}

/**
 * 로그인 뒤 돌아갈 곳으로 삼으면 안 되는 경로들. `/login?next=/login` 은 무의미하고,
 * `/logout` 은 방금 한 로그인을 즉시 되돌리며, `/auth/google/callback` 은 이미 소비된
 * code·state 를 다시 들고 가 실패한다.
 */
const AUTH_ROUTES = ["/login", "/signup", "/logout", "/auth"]

function isAuthRoute(pathname: string): boolean {
    return AUTH_ROUTES.some((route) => pathname === route || pathname.startsWith(`${route}/`))
}

/**
 * "지금 있는 곳" 을 `next` 로 쓸 수 있는 형태로 만든다. 어느 경로에서나 렌더되는 헤더의
 * 로그인 버튼처럼, 목적지가 고정돼 있지 않고 현재 위치인 경우를 위한 것이다.
 *
 * 돌아갈 이유가 없으면(홈이거나, 인증 화면 자체이거나, 경로를 읽을 수 없으면) null 을
 * 돌려준다 — buildAuthHref 가 null 을 받으면 파라미터 없는 맨 링크를 만든다.
 *
 * @param pathname usePathname() 의 결과
 * @param query    useSearchParams().toString() 의 결과. `?` 는 있어도 없어도 된다.
 */
export function returnPathFor(
    pathname: string | null | undefined,
    query?: string | null
): string | null {
    if (typeof pathname !== "string" || !pathname.startsWith("/") || isAuthRoute(pathname)) {
        return null
    }

    const search = typeof query === "string" ? query.replace(/^\?/, "") : ""
    const safe = sanitizeNextPath(search ? `${pathname}?${search}` : pathname)
    return safe === HOME_PATH ? null : safe
}

const PENDING_PREFIX = "woobeee:auth:next:"
// 서버가 state 를 살려 두는 시간과 같게 맞춘다 — app-mvc 의
// google.oauth.authorization-state-ttl-seconds 기본값 600초. 이보다 오래 걸린 왕복은 어차피
// 서버가 "Invalid authorization state" 로 거절하므로, 목적지만 남겨 둘 이유가 없다.
const PENDING_TTL_MS = 10 * 60 * 1000

interface PendingRedirect {
    next: string
    createdAt: number
}

function pendingKey(state: string): string {
    return `${PENDING_PREFIX}${state}`
}

/**
 * Google 로 떠나기 직전에 호출한다. 목적지가 홈이면 아무것도 남기지 않는다 — 남길 것이 없고,
 * 남기지 않는 편이 "이번 시도에는 돌아갈 곳이 없다" 를 더 정확히 표현한다.
 */
export function rememberPendingRedirect(state: string, next: string | null | undefined): void {
    if (typeof window === "undefined" || !state) {
        return
    }

    const safe = sanitizeNextPath(next)
    if (safe === HOME_PATH) {
        return
    }

    prunePendingRedirects()

    const entry: PendingRedirect = { next: safe, createdAt: Date.now() }
    try {
        window.sessionStorage.setItem(pendingKey(state), JSON.stringify(entry))
    } catch {
        // 사파리 프라이빗 모드 등에서 저장이 막힐 수 있다. 저장 실패로 로그인 자체를 막을
        // 이유는 없으므로 복귀만 포기하고 홈으로 떨어진다.
    }
}

/**
 * 콜백에서 정확히 한 번 꺼낸다. 읽자마자 지우므로, 인증이 실패해 목적지를 쓰지 못하더라도
 * 그 항목이 다음 로그인을 납치하지 못한다 — 호출부는 성공/실패를 가리기 전에 이것을 부른다.
 */
export function consumePendingRedirect(state: string | null | undefined): string {
    if (typeof window === "undefined" || !state) {
        return HOME_PATH
    }

    const key = pendingKey(state)
    let raw: string | null = null
    try {
        raw = window.sessionStorage.getItem(key)
        window.sessionStorage.removeItem(key)
    } catch {
        return HOME_PATH
    }
    if (!raw) {
        return HOME_PATH
    }

    const entry = parsePendingRedirect(raw)
    if (!entry || Date.now() - entry.createdAt >= PENDING_TTL_MS) {
        return HOME_PATH
    }

    // 넣을 때 이미 걸렀지만 한 번 더 본다. sessionStorage 는 같은 오리진의 아무 스크립트나
    // 쓸 수 있고, 이 값은 곧바로 이동에 쓰인다.
    return sanitizeNextPath(entry.next)
}

function parsePendingRedirect(raw: string): PendingRedirect | null {
    let value: unknown
    try {
        value = JSON.parse(raw)
    } catch {
        return null
    }
    if (typeof value !== "object" || value === null) {
        return null
    }
    const candidate = value as Partial<PendingRedirect>
    if (typeof candidate.next !== "string" || candidate.next.length === 0) {
        return null
    }
    if (typeof candidate.createdAt !== "number" || !Number.isFinite(candidate.createdAt)) {
        return null
    }
    return { next: candidate.next, createdAt: candidate.createdAt }
}

/**
 * 중간에 그만둔 인증 시도가 남긴 항목을 치운다. 키가 state 별로 다르니 다음 로그인을 납치할
 * 수는 없지만, 탭이 살아 있는 동안 무한정 쌓이게 둘 이유도 없다.
 */
function prunePendingRedirects(): void {
    try {
        const storage = window.sessionStorage
        const stale: string[] = []
        for (let index = 0; index < storage.length; index += 1) {
            const key = storage.key(index)
            if (!key || !key.startsWith(PENDING_PREFIX)) {
                continue
            }
            const entry = parsePendingRedirect(storage.getItem(key) ?? "")
            if (!entry || Date.now() - entry.createdAt >= PENDING_TTL_MS) {
                stale.push(key)
            }
        }
        stale.forEach((key) => storage.removeItem(key))
    } catch {
        // 접근이 막혔으면 정리할 것도 없다.
    }
}
