// front/lib/blog-redirect.ts — /blog 는 / 로 옮겼다. 옛 링크·북마크의 필터 쿼리를 홈으로 옮겨 준다.

const CARRIED_KEYS = ["category", "search", "tag"] as const

type Query = URLSearchParams | Record<string, string | string[] | undefined>

/** category·search·tag 만 옮긴다. 배열이면 첫 값, 빈 값은 버린다. 아무것도 없으면 "/". */
export function blogRedirectTarget(query: Query): string {
    const out = new URLSearchParams()
    for (const key of CARRIED_KEYS) {
        const raw = query instanceof URLSearchParams ? query.get(key) : query[key]
        const value = Array.isArray(raw) ? raw[0] : raw
        if (value) out.set(key, value)
    }
    const qs = out.toString()
    return qs ? `/?${qs}` : "/"
}
