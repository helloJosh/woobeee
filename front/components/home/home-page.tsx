"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { Loader2, PenSquare, RefreshCw, X } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import CategoryBar from "@/components/home/category-bar"
import HeroPost from "@/components/home/hero-post"
import PostCard from "@/components/home/post-card"
import TagChips from "@/components/home/tag-chips"
import MinimalScrollToTop from "@/components/minimal-scroll-to-top"
import { useCategories } from "@/hooks/use-categories"
import { useInfinitePosts } from "@/hooks/use-infinite-posts"
import { useRegisterHeaderControls } from "@/hooks/use-header-controls"
import { tagsAPI, tokenManager, type PopularTag } from "@/lib/api"
import { canManagePosts } from "@/lib/blog-admin"

const PAGE_SIZE = 12

/**
 * 홈 = 기술블로그. 우아한 기술블로그의 골격: 상단 카테고리 내비 → 인기 태그 → 최신 글 1건 히어로 →
 * 카드 그리드(무한 스크롤). 필터(category·search·tag)는 전부 URL 쿼리에 산다 — 새로고침·공유에 살아남는다.
 */
export default function HomePage() {
    const router = useRouter()
    const searchParams = useSearchParams()
    const { categories } = useCategories()

    const categoryParam = searchParams.get("category")
    const category = categoryParam && !Number.isNaN(Number(categoryParam)) ? Number(categoryParam) : null
    const search = searchParams.get("search") || null
    const tag = searchParams.get("tag") || null

    const [popularTags, setPopularTags] = useState<PopularTag[]>([])
    const [canWrite, setCanWrite] = useState(false)

    useEffect(() => {
        setCanWrite(canManagePosts(tokenManager.getRole()))
        let cancelled = false
        tagsAPI.popular(20).then((t) => { if (!cancelled) setPopularTags(t) }).catch(() => { if (!cancelled) setPopularTags([]) })
        return () => { cancelled = true }
    }, [])

    const { posts, loading, error, hasMore, loadMore, loadMoreRef, refresh } = useInfinitePosts({
        categoryId: category ?? undefined,
        search: search ?? undefined,
        tag: tag ?? undefined,
        pageSize: PAGE_SIZE,
    })

    const update = useCallback((patch: { category?: number | null; search?: string | null; tag?: string | null }) => {
        const next = new URLSearchParams()
        const c = patch.category === undefined ? category : patch.category
        const s = patch.search === undefined ? search : patch.search
        const t = patch.tag === undefined ? tag : patch.tag
        if (c !== null) next.set("category", String(c))
        if (s) next.set("search", s)
        if (t) next.set("tag", t)
        const qs = next.toString()
        router.push(qs ? `/?${qs}` : "/", { scroll: false })
    }, [router, category, search, tag])

    useRegisterHeaderControls({
        searchQuery: search,
        onSearchChange: (q) => update({ search: q || null }),
    })

    const selectTag = useCallback((name: string) => {
        update({ tag: tag && tag.toLowerCase() === name.toLowerCase() ? null : name })
    }, [update, tag])

    const unfiltered = category === null && !search && !tag
    const hero = unfiltered && posts.length > 0 ? posts[0] : null
    const gridPosts = useMemo(() => (hero ? posts.slice(1) : posts), [hero, posts])
    const popularAsTags = useMemo(() => popularTags.map((t) => ({ id: t.id, name: t.name })), [popularTags])

    return (
        <main className="mx-auto max-w-6xl space-y-6 p-4 sm:p-6">
            <div className="space-y-3">
                <div className="flex items-start justify-between gap-4">
                    <CategoryBar categories={categories} selectedId={category} onSelect={(id) => update({ category: id })} />
                    {canWrite ? (
                        <Button size="sm" className="shrink-0" onClick={() => router.push("/blog/write")}>
                            <PenSquare className="mr-1.5 h-4 w-4" />글쓰기
                        </Button>
                    ) : null}
                </div>
                {popularAsTags.length > 0 ? (
                    <TagChips tags={popularAsTags} activeTag={tag} onSelect={selectTag} />
                ) : null}
            </div>

            {(search || tag) ? (
                <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                    {search ? (
                        <button type="button" onClick={() => update({ search: null })}
                                className="flex items-center gap-1 rounded-full border px-3 py-1 hover:bg-muted">
                            검색: <span className="font-medium text-foreground">“{search}”</span> <X className="h-3.5 w-3.5" />
                        </button>
                    ) : null}
                    {tag ? (
                        <button type="button" onClick={() => update({ tag: null })}
                                className="flex items-center gap-1 rounded-full border px-3 py-1 hover:bg-muted">
                            태그: <span className="font-medium text-foreground">#{tag}</span> <X className="h-3.5 w-3.5" />
                        </button>
                    ) : null}
                </div>
            ) : null}

            {error && posts.length === 0 ? (
                <Alert variant="destructive">
                    <AlertDescription className="flex items-center justify-between">
                        <span>{error}</span>
                        <Button variant="outline" size="sm" onClick={refresh} className="ml-4 bg-transparent">
                            <RefreshCw className="mr-2 h-4 w-4" />다시 시도
                        </Button>
                    </AlertDescription>
                </Alert>
            ) : null}

            {hero ? <HeroPost post={hero} onSelectTag={selectTag} /> : null}

            {gridPosts.length > 0 ? (
                <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
                    {gridPosts.map((post) => (
                        <PostCard key={post.id} post={post} activeTag={tag} onSelectTag={selectTag} />
                    ))}
                </div>
            ) : null}

            {!loading && !error && posts.length === 0 ? (
                <div className="rounded-xl border border-dashed p-12 text-center text-sm text-muted-foreground">
                    {unfiltered ? "아직 글이 없습니다." : "조건에 맞는 글이 없습니다."}
                </div>
            ) : null}

            {loading ? (
                <div className="flex items-center justify-center gap-2 py-10 text-muted-foreground">
                    <Loader2 className="h-5 w-5 animate-spin" /><span>글을 불러오는 중…</span>
                </div>
            ) : null}

            {hasMore && !loading ? (
                <div ref={loadMoreRef} className="flex justify-center py-6">
                    <Button variant="outline" onClick={loadMore} className="w-full max-w-xs bg-transparent">더 보기</Button>
                </div>
            ) : null}

            <MinimalScrollToTop threshold={200} />
        </main>
    )
}
