"use client"

import dynamic from "next/dynamic"

// 편집기는 localStorage 권한 판정에 기대므로 클라이언트에서만 그린다
const PostEditor = dynamic(() => import("@/components/blog/post-editor"), {
    ssr: false,
})

export default function BlogWritePage() {
    return <PostEditor />
}
