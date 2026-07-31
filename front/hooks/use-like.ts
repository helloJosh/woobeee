// hooks/useLike.ts
"use client"

import { useState } from "react"
import { likeAPI } from "@/lib/api"

export function useLike(postId: number, initialLikes: number, initiallyLiked = false) {
    const [likes, setLikes] = useState(initialLikes)
    const [isLiked, setIsLiked] = useState(initiallyLiked)

    const toggleLike = async () => {
        try {
            if (isLiked) {
                await likeAPI.deleteLike(postId)
                setLikes((prev) => prev - 1)
            } else {
                await likeAPI.addLike(postId)
                setLikes((prev) => prev + 1)
            }
            setIsLiked(!isLiked)
        } catch (err) {
            console.error("좋아요 처리 중 오류 발생:", err)
        }
    }

    return {
        likes,
        isLiked,
        toggleLike,
    }
}