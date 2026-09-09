"use client"

import Link from "next/link"
import { ArrowRight } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import PostThumbnail from "@/components/home/post-thumbnail"
import PostMeta from "@/components/home/post-meta"
import TagChips from "@/components/home/tag-chips"
import { excerpt } from "@/lib/post-preview"
import type { Post } from "@/lib/types"

/** 필터가 없을 때 최신 글 1건을 크게 — 우아한 기술블로그의 첫 화면 골격. */
export default function HeroPost({ post, onSelectTag }: { post: Post; onSelectTag: (name: string) => void }) {
    return (
        <section aria-label="최신 글" className="grid overflow-hidden rounded-2xl border bg-card md:grid-cols-5">
            <Link href={`/blog/${post.id}`} className="block md:col-span-3">
                <div className="aspect-video h-full w-full overflow-hidden bg-muted md:aspect-auto md:min-h-[320px]">
                    <PostThumbnail content={post.content} categoryName={post.categoryName} />
                </div>
            </Link>
            <div className="flex flex-col justify-between gap-4 p-6 md:col-span-2 md:p-8">
                <div className="space-y-3">
                    <div className="flex flex-wrap items-center gap-2">
                        <Badge variant="outline">{post.categoryName}</Badge>
                        <span className="text-xs font-medium uppercase tracking-wider text-muted-foreground">최신 글</span>
                    </div>
                    <Link href={`/blog/${post.id}`} className="block">
                        <h2 className="text-2xl font-bold leading-tight tracking-tight hover:text-primary md:text-3xl">{post.title}</h2>
                    </Link>
                    <p className="line-clamp-3 text-sm leading-relaxed text-muted-foreground md:text-base">{excerpt(post.content, 260)}</p>
                    <TagChips tags={post.tags} onSelect={onSelectTag} />
                </div>
                <div className="flex items-center justify-between">
                    <PostMeta post={post} />
                    <Link href={`/blog/${post.id}`} className="flex items-center gap-1 text-sm font-medium hover:underline">
                        읽기 <ArrowRight className="h-4 w-4" />
                    </Link>
                </div>
            </div>
        </section>
    )
}
