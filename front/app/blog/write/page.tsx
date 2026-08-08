"use client"

import dynamic from "next/dynamic"

// BlockNote는 브라우저 전용 — SSR에서 그리면 안 된다
const PostEditor = dynamic(() => import("@/components/blog/post-editor"), {
    ssr: false,
})

export default function BlogWritePage() {
    return <PostEditor />
}
