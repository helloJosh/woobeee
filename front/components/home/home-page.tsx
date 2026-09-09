"use client"

import { useCallback, useEffect, useState } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { Loader2, Menu, PenSquare, RefreshCw, X } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetTrigger } from "@/components/ui/sheet"
import HomeSidebar from "@/components/home/home-sidebar"
import TagBar from "@/components/home/tag-bar"
import PostListItem from "@/components/home/post-list-item"
import MinimalScrollToTop from "@/components/minimal-scroll-to-top"
import { useCategories } from "@/hooks/use-categories"
import { useInfinitePosts } from "@/hooks/use-infinite-posts"
import { useRegisterHeaderControls } from "@/hooks/use-header-controls"
import { tagsAPI, tokenManager, type PopularTag } from "@/lib/api"
import { canManagePosts } from "@/lib/blog-admin"

const PAGE_SIZE = 10

/**
 * 홈 = 기술블로그. 우아한 기술블로그의 두 열: 왼쪽은 글을 세로로(날짜·카테고리, 큰 제목, 요약, 태그 — 이미지 없음),
 * 오른콝은 「카테고리」(접고 펼침)와 「태그」 목록. 필터(category·search·tag)는 전부 URL 쿼리에 산다.
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
        tagsAPI.popular(50).then((t) => { if (!cancelled) setPopularTags(t) }).catch(() => { if (!cancelled) setPopularTags([]) })
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
    const sidebar = (
        <HomeSidebar
            categories={categories} selectedCategory={category} onSelectCategory={(id) => update({ category: id })}
            tags={popularTags.slice(0, 20)} activeTag={tag} onSelectTag={selectTag}
        />
    )

    return (
        <main className="mx-auto max-w-4xl px-4 py-6 sm:px-6 sm:py-10">
          {/* 우아한 홈 구조: 전체 폭 가운데의 태그 알약 줄 → 큰 여백 → 두 열(글 목록 · 사이드바)이 같은 높이에서 시작 */}
          {canWrite ? (
              <div className="flex justify-end">
                  <Button size="sm" onClick={() => router.push("/blog/write")}>
                      <PenSquare className="mr-1.5 h-4 w-4" />글쓰기
                  </Button>
              </div>
          ) : null}
          <TagBar tags={popularTags} activeTag={tag} onSelectTag={selectTag} onClear={() => update({ tag: null })} />
          <div className="mt-10 sm:mt-20 lg:grid lg:grid-cols-[minmax(0,1fr)_200px] lg:gap-12">
          <div className="min-w-0 space-y-4">
            {/* 휴대폰 폭: 목록만 보이고, 카테고리·태그는 ≡ 서랍에서 고른다(우아한 모바일 홈처럼) */}
            <div className="flex justify-end lg:hidden">
                <Sheet>
                    <SheetTrigger asChild>
                        <Button variant="ghost" size="sm" aria-label="카테고리·태그 열기">
                            <Menu className="mr-1.5 h-4 w-4" />카테고리 · 태그
                        </Button>
                    </SheetTrigger>
                    <SheetContent side="right" className="w-[280px] overflow-y-auto">
                        <SheetHeader><SheetTitle className="text-left">둘러보기</SheetTitle></SheetHeader>
                        <div className="mt-6">{sidebar}</div>
                    </SheetContent>
                </Sheet>
            </div>

            {(search || tag || category !== null) ? (
                <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
                    {category !== null ? (
                        <button type="button" onClick={() => update({ category: null })}
                                className="flex items-center gap-1 rounded-full border px-3 py-1 hover:bg-muted">
                            카테고리: <span className="font-medium text-foreground">{categories.flatMap((c) => [c, ...(c.children ?? [])]).find((c) => c.id === category)?.name ?? category}</span> <X className="h-3.5 w-3.5" />
                        </button>
                    ) : null}
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

            {posts.length > 0 ? (
                <div className="divide-y">
                    {posts.map((post) => (
                        <PostListItem key={post.id} post={post} />
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
          </div>
          <div className="hidden lg:block">{sidebar}</div>
          </div>
        </main>
    )
}
