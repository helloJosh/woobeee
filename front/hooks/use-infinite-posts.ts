"use client"

import { useState, useEffect, useCallback, useRef } from "react"
import { postsAPI } from "@/lib/api"
import type { Post , PostsParams, GetPostsResponse} from "@/lib/types"
import  {mockPostResponseApi , mockPostResponseApiWithCategory, mockPostResponseApiWithSearch, mockPostResponseApiWithCategoryAndSearch} from "@/lib/mock-data"

interface UseInfinitePostsProps {
    categoryId?: number
    search?: string
    /** 태그 이름 필터 (BLOG-AC-21). */
    tag?: string
    pageSize?: number
    enabled?: boolean
}

export function useInfinitePosts ({
                                      categoryId,
                                      search,
                                      tag,
                                      pageSize = 5,
                                      enabled = true
}: UseInfinitePostsProps = {})
{
    const [posts, setPosts] = useState<Post[]>([])
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [hasMore, setHasMore] = useState(true)
    const [currentPage, setCurrentPage] = useState(0)

    const observerRef = useRef<IntersectionObserver | null>(null)
    const loadMoreRef = useRef<HTMLDivElement | null>(null)
    const isInitialLoad = useRef(true)

    // 초기 데이터 로드 및 검색/카테고리 변경 시 리셋
    useEffect(() => {
        if (!enabled) return

        const resetAndLoad = async () => {
            setPosts([])
            setCurrentPage(0)
            setHasMore(true)
            setError(null)
            isInitialLoad.current = true

            await loadPosts(0, true)
        }

        resetAndLoad()
    }, [categoryId, search, tag, enabled, pageSize])

    // 포스트 로드 함수
    const loadPosts = useCallback(
        async (page: number, reset = false) => {
            if (loading) return

            try {
                setLoading(true)
                setError(null)

                const q = (search ?? "").trim()
                const hasCategory = typeof categoryId === "number" && !Number.isNaN(categoryId)
                const hasSearch = q.length > 0
                const t = (tag ?? "").trim()

                const params: PostsParams = {
                    page,
                    size: pageSize,
                    ...(hasCategory && { categoryId }),
                    ...(hasSearch && { q }),
                    ...(t.length > 0 && { tag: t }),
                }

                let response: GetPostsResponse

                response = await postsAPI.getPosts(params)

                // if (hasCategory && hasSearch) {
                //     console.log("📦 using mockPostResponseApiWithCategoryAndSearch")
                //     response = mockPostResponseApiWithCategoryAndSearch.data
                // } else if (hasCategory) {
                //     console.log("📦 using mockPostResponseApiWithCategory")
                //     response = mockPostResponseApiWithCategory.data
                // } else if (hasSearch) {
                //     console.log("📦 using mockPostResponseApiWithSearch")
                //     response = mockPostResponseApiWithSearch.data
                // } else {
                //     console.log("📦 using default mockPostResponseApi")
                //     response = mockPostResponseApi.data
                // }

                const newPosts = Array.isArray(response.contents) ? response.contents : []

                if (reset) {
                    setPosts(newPosts)
                } else {
                    setPosts((prev) => [...(prev || []), ...newPosts])
                }

                setHasMore(response.hasNext)
                setCurrentPage(page)
            } catch (err) {
                const errorMessage = err instanceof Error ? err.message : "포스트를 불러오는데 실패했습니다."
                setError(errorMessage)
                console.error("Failed to load posts:", err)

                // 에러 발생 시에도 빈 배열 보장
                if (reset) {
                    setPosts([])
                }
            } finally {
                setLoading(false)
                isInitialLoad.current = false
            }
        },
        [categoryId, search, tag, pageSize, loading],
    )

    // 다음 페이지 로드
    const loadMore = useCallback(() => {
        if (!hasMore || loading) return
        loadPosts(currentPage + 1)
    }, [hasMore, loading, currentPage, loadPosts])

    // Intersection Observer 설정
    useEffect(() => {
        if (!enabled || !hasMore || loading) return

        if (observerRef.current) {
            observerRef.current.disconnect()
        }

        observerRef.current = new IntersectionObserver(
            (entries) => {
                const target = entries[0]
                if (target.isIntersecting && hasMore && !loading && !isInitialLoad.current) {
                    loadMore()
                }
            },
            {
                threshold: 0.1,
                rootMargin: "100px",
            },
        )

        if (loadMoreRef.current) {
            observerRef.current.observe(loadMoreRef.current)
        }

        return () => {
            if (observerRef.current) {
                observerRef.current.disconnect()
            }
        }
    }, [loadMore, hasMore, loading, enabled])

    // 새로고침
    const refresh = useCallback(() => {
        setPosts([])
        setCurrentPage(0)
        setHasMore(true)
        setError(null)
        isInitialLoad.current = true
        loadPosts(0, true)
    }, [loadPosts])

    // totalElements는 현재 로드된 포스트 수로 대체
    const totalElements = posts.length

    return {
        posts,
        loading,
        error,
        hasMore,
        totalElements, // 현재 로드된 포스트 수
        currentPage,
        loadMore,
        loadMoreRef,
        refresh,
    }
}
