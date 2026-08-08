"use client"

import dynamic from "next/dynamic"
import { useParams } from "next/navigation"

const PostEditor = dynamic(() => import("@/components/blog/post-editor"), {
    ssr: false,
})

export default function BlogEditPage() {
    const params = useParams<{ postId: string }>()
    const postId = Number(params.postId)

    if (!Number.isFinite(postId)) {
        return null
    }

    return <PostEditor postId={postId} />
}
