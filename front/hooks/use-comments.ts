"use client"

import { useCallback, useEffect, useState } from "react"
import { commentAPI } from "@/lib/api"
import type { Comment } from "@/lib/types"

function normalize(list: any[] = []): Comment[] {
    return list.map((c) => ({
        id: c.id,
        author: c.author ?? "익명",
        isEditable: c.isEditable ,
        content: c.content ?? "",
        createdAt: c.createdAt ? new Date(c.createdAt) : new Date(),
        replies: normalize(c.replies ?? []),
    }))
}

export function useComments(postId: number, userId?: string) {
    const [comments, setComments] = useState<Comment[]>([])
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState<string | null>(null)

    const loadComments = useCallback(async () => {
        try {
            setLoading(true)
            setError(null)
            const res = await commentAPI.getAllFromPost(postId, userId)
            // res: ApiResponse<List<GetCommentResponse>>
            setComments(normalize(res.data ?? []))
        } catch (e: any) {
            console.error(e)
            setError(e?.message ?? "댓글을 불러오지 못했습니다.")
            setComments([])
        } finally {
            setLoading(false)
        }
    }, [postId, userId])

    useEffect(() => {
        loadComments()
    }, [loadComments])

    const addComment = async (content: string) => {
        await commentAPI.saveComment(
            { postId, parentId: null, content }, // PostCommentRequest
            userId,
        )
        await loadComments()
    }

    const addReply = async (parentId: number, content: string) => {
        await commentAPI.saveComment(
            { postId, parentId, content },
            userId,
        )
        await loadComments()
    }

    const deleteComment = async (commentId: number) => {
        await commentAPI.deleteComment(commentId, userId)
        await loadComments()
    }

    return {
        comments,
        loading,
        error,
        addComment,
        addReply,
        deleteComment,
        refresh: loadComments,
    }
}