import type { ApiResponse, GetPostResponse, GetPostsResponse, PostsParams } from "@/lib/types"

/**
 * 서버 컴포넌트(크롤러가 보는 HTML)용 블로그 조회.
 *
 * `lib/api.ts` 의 postsAPI 는 브라우저 전용이다 — 토큰·쿠키·alert 를 쓰고 상대 경로(`/api/back/...`)로
 * 부른다. 서버에서는 rewrites 를 거치지 않고 app-mvc 를 직접 부른다(`MVC_ORIGIN`).
 * 실패는 던지지 않고 null 로 돌려준다 — SEO 용 선(先)조회가 실패해도 클라이언트가 다시 받는다.
 */
const MVC_ORIGIN = process.env.MVC_ORIGIN || "http://localhost:8000"

/** 글은 자주 안 바뀌고 크롤러는 몰려온다 — 짧게 캐시해 백엔드를 보호한다. */
const REVALIDATE_SECONDS = 60

async function getJson<T>(path: string): Promise<T | null> {
    try {
        const res = await fetch(`${MVC_ORIGIN}${path}`, { next: { revalidate: REVALIDATE_SECONDS } })
        if (!res.ok) return null
        const body: ApiResponse<T> = await res.json()
        return body.data ?? null
    } catch {
        return null
    }
}

export function fetchPost(postId: number): Promise<GetPostResponse | null> {
    if (!Number.isInteger(postId) || postId <= 0) return Promise.resolve(null)
    return getJson<GetPostResponse>(`/api/back/posts/${postId}`)
}

export function fetchPosts(params: PostsParams = {}): Promise<GetPostsResponse | null> {
    const qs = new URLSearchParams()
    if (params.page !== undefined) qs.set("page", String(params.page))
    if (params.size !== undefined) qs.set("size", String(params.size))
    if (params.q?.trim()) qs.set("q", params.q)
    if (params.categoryId !== undefined) qs.set("categoryId", String(params.categoryId))
    if (params.tag?.trim()) qs.set("tag", params.tag.trim())
    return getJson<GetPostsResponse>(`/api/back/posts?${qs.toString()}`)
}

/** 마크다운 기호를 걷어 낸 메타 description 용 요약. */
export function summarize(markdown: string, max = 160): string {
    const plain = markdown
        .replace(/```[\s\S]*?```/g, " ")
        .replace(/!\[[^\]]*]\([^)]*\)/g, " ")
        .replace(/\[([^\]]*)]\([^)]*\)/g, "$1")
        .replace(/[#>*_`~|-]/g, " ")
        .replace(/\s+/g, " ")
        .trim()
    return plain.length > max ? `${plain.slice(0, max - 1)}…` : plain
}
