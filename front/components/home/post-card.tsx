"use client"

import Link from "next/link"
import { Badge } from "@/components/ui/badge"
import PostThumbnail from "@/components/home/post-thumbnail"
import PostMeta from "@/components/home/post-meta"
import TagChips from "@/components/home/tag-chips"
import { excerpt } from "@/lib/post-preview"
import type { Post } from "@/lib/types"

/** 그리드 카드 — 16:9 썸네일, 카테고리, 제목 2줄, 요약 2줄, 태그, 메타. */
export default function PostCard({ post, activeTag, onSelectTag }: {
    post: Post
    activeTag: string | null
    onSelectTag: (name: string) => void
}) {
    return (
        <article className="group flex flex-col overflow-hidden rounded-xl border bg-card transition-shadow hover:shadow-md">
            <Link href={`/blog/${post.id}`} className="block">
                <div className="aspect-video w-full overflow-hidden bg-muted">
                    <PostThumbnail content={post.content} categoryName={post.categoryName}
                                   className="transition-transform duration-300 group-hover:scale-[1.03]" />
                </div>
                <div className="space-y-2 p-4 pb-2">
                    <Badge variant="outline" className="text-[11px]">{post.categoryName}</Badge>
                    <h3 className="line-clamp-2 text-lg font-semibold leading-snug group-hover:text-primary">{post.title}</h3>
                    <p className="line-clamp-2 text-sm text-muted-foreground">{excerpt(post.content, 160)}</p>
                </div>
            </Link>
            <div className="mt-auto space-y-2 px-4 pb-4">
                <TagChips tags={post.tags} activeTag={activeTag} onSelect={onSelectTag} />
                <PostMeta post={post} />
            </div>
        </article>
    )
}
