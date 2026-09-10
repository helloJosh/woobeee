"use client"

import Link from "next/link"
import type { Post } from "@/lib/types"

function formatDate(value: Date | string | undefined): string {
    if (!value) return ""
    const d = new Date(value)
    const mm = String(d.getMonth() + 1).padStart(2, "0")
    const dd = String(d.getDate()).padStart(2, "0")
    return `${d.getFullYear()}. ${mm}. ${dd}.`
}

/** 글 한 줄 — 날짜·카테고리, 큰 제목, 작성자가 적은 설명(없으면 생략). 태그·본문 요약·이미지 없음(태그는 상단 알약 줄과 사이드바에서만). */
export default function PostListItem({ post }: { post: Post }) {
    return (
        <article className="space-y-3 py-10 first:pt-0">
            <p className="text-sm text-muted-foreground">
                <time dateTime={new Date(post.createdAt).toISOString()}>{formatDate(post.createdAt)}</time>
                <span className="ml-3 font-medium text-foreground/80">{post.categoryName}</span>
            </p>
            <h2 className="text-2xl font-bold leading-tight tracking-tight sm:text-3xl">
                <Link href={`/blog/${post.id}`} className="transition-colors hover:text-primary">{post.title}</Link>
            </h2>
            {post.description ? (
                <p className="text-base leading-relaxed text-muted-foreground">{post.description}</p>
            ) : null}
        </article>
    )
}
