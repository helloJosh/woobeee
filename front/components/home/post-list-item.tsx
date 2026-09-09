"use client"

import Link from "next/link"
import TagChips from "@/components/home/tag-chips"
import { excerpt } from "@/lib/post-preview"
import type { Post } from "@/lib/types"

function formatDate(value: Date | string | undefined): string {
    if (!value) return ""
    const d = new Date(value)
    const mm = String(d.getMonth() + 1).padStart(2, "0")
    const dd = String(d.getDate()).padStart(2, "0")
    return `${d.getFullYear()}. ${mm}. ${dd}.`
}

/** 글 한 줄 — 날짜·카테고리, 큰 제목, 요약, 태그. 이미지 없음. */
export default function PostListItem({ post, activeTag, onSelectTag }: {
    post: Post
    activeTag: string | null
    onSelectTag: (name: string) => void
}) {
    return (
        <article className="space-y-3 py-10 first:pt-2">
            <p className="text-sm text-muted-foreground">
                <time dateTime={new Date(post.createdAt).toISOString()}>{formatDate(post.createdAt)}</time>
                <span className="ml-3 font-medium text-foreground/80">{post.categoryName}</span>
            </p>
            <h2 className="text-2xl font-bold leading-tight tracking-tight sm:text-3xl">
                <Link href={`/blog/${post.id}`} className="hover:underline decoration-2 underline-offset-4">{post.title}</Link>
            </h2>
            <p className="line-clamp-2 text-base leading-relaxed text-muted-foreground">{excerpt(post.content, 220)}</p>
            <TagChips tags={post.tags} activeTag={activeTag} onSelect={onSelectTag} />
        </article>
    )
}
