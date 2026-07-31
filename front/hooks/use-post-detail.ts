"use client"

import { useEffect, useState } from "react"
import { postsAPI } from "@/lib/api"
import type {GetPostResponse} from "@/lib/types"

export function usePostDetail(postId: number) {
    const [post, setPost] = useState<GetPostResponse | null>(null)
    const [loading, setLoading] = useState(true)
    const [error, setError] = useState<string | null>(null)

    useEffect(() => {
        const fetchPost = async () => {
            try {
                const response = await postsAPI.getPost(Number(postId))
                setPost(response)
                console.log(response)
            } catch (err) {
                console.error(err)
                setError("포스트를 불러오는데 실패했습니다.")
            } finally {
                setLoading(false)
            }
        }

        fetchPost()
    }, [postId])

    return {
        post,
        loading,
        error,
    }
}