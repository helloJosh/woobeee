"use client"

import { useEffect, useState } from "react"
import { postsAPI } from "@/lib/api"
import type { GetPostResponse } from "@/lib/types"

/**
 * 글 상세를 받아 온다.
 *
 * <p>취소(abort)가 이 훅의 요점이다. 정리 없이 두면 두 가지가 깨진다 — 언마운트된 뒤 도착한
 * 응답이 사라진 화면에 상태를 쓰고, `postId` 가 빠르게 바뀌면 먼저 보낸 요청이 나중에 도착해
 * **이전 글이 최종 상태로 남는다**(경주). 정리에서 취소하면 둘 다 없어진다.
 *
 * <p>덤으로 개발 모드의 이중 호출도 줄어든다. React Strict Mode 는 이펙트를 두 번 실행하는데,
 * 그 사이 정리가 첫 요청을 취소하므로 응답을 쓰는 것은 하나뿐이다. Strict Mode 가 드러내려는
 * 결함이 바로 이 정리 부재였다.
 */
export function usePostDetail(postId: number) {
    const [post, setPost] = useState<GetPostResponse | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        const controller = new AbortController()

        const fetchPost = async () => {
            // postId 가 바뀌어 다시 받는 경우가 있으므로 상태를 초기화한다. 안 하면 새 글을
            // 기다리는 동안 이전 글의 오류 문구가 남는다.
            setLoading(true)
            setError(null)

            try {
                const response = await postsAPI.getPost(Number(postId), controller.signal)
                setPost(response)
            } catch (cause) {
                // 취소는 오류가 아니다 — 화면에 실패 문구를 띄우면 안 된다.
                if (controller.signal.aborted) {
                    return
                }
                console.error(cause)
                setError("포스트를 불러오는데 실패했습니다.")
            } finally {
                if (!controller.signal.aborted) {
                    setLoading(false)
                }
            }
        }

        void fetchPost()

        return () => controller.abort()
    }, [postId])

    return {
        post,
        loading,
        error,
    }
}
