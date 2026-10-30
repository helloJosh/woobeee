import { Suspense } from "react"
import type { Metadata } from "next"
import HomePage from "@/components/home/home-page"
import { fetchPosts } from "@/lib/server-blog"

// 기술블로그. 홈(/)이 공동구매가 되면서 여기로 내려왔다.
// HomePage 가 useSearchParams 를 읽으므로 Suspense 경계가 필요하다.
//
// 첫 쪽은 서버에서 받아 HTML 에 담는다 — 크롤러가 글 목록과 /blog/{id} 링크를 찾는다.
// 필터(category·search·tag)는 클라이언트(HomePage)와 같은 URL 쿼리에서 읽어 같은 조건으로 받는다.
const PAGE_SIZE = 10 // HomePage 의 PAGE_SIZE 와 같아야 한다

export const metadata: Metadata = {
    title: "Blog",
    description: "Backend developer 의 개발과 배포 기록을 남기는 기술 블로그",
    alternates: { canonical: "https://woobeee.com/blog" },
}

type Query = Record<string, string | string[] | undefined>
const one = (v: string | string[] | undefined) => (Array.isArray(v) ? v[0] : v)

export default async function BlogPage({ searchParams }: { searchParams: Query }) {
    const categoryRaw = one(searchParams.category)
    const category = categoryRaw && !Number.isNaN(Number(categoryRaw)) ? Number(categoryRaw) : undefined

    const initialPage = await fetchPosts({
        page: 0,
        size: PAGE_SIZE,
        categoryId: category,
        q: one(searchParams.search),
        tag: one(searchParams.tag),
    })

    return (
        <Suspense fallback={null}>
            <HomePage initialPage={initialPage} />
        </Suspense>
    )
}
