// front/lib/blog-redirect.ts — 블로그가 / 에서 /blog 로 돌아갔다(홈은 공동구매다).
// 한때 반대로 옮긴 적이 있어 `/?category=3` 같은 옛 링크·북마크가 돌아다닌다. 그 필터 쿼리를
// 잃지 않게 /blog 로 넘긴다.

const CARRIED_KEYS = ["category", "search", "tag"] as const

type Query = URLSearchParams | Record<string, string | string[] | undefined>

/**
 * 홈에 붙어 온 블로그 필터를 /blog 로 옮길 주소. 옮길 것이 없으면 null —
 * 그냥 홈에 들어온 사람까지 블로그로 보내면 안 된다.
 */
export function blogFilterTarget(query: Query): string | null {
    const out = new URLSearchParams()
    for (const key of CARRIED_KEYS) {
        const raw = query instanceof URLSearchParams ? query.get(key) : query[key]
        const value = Array.isArray(raw) ? raw[0] : raw
        if (value) out.set(key, value)
    }
    const qs = out.toString()
    return qs ? `/blog?${qs}` : null
}
