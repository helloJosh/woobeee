import { Eye, Heart } from "lucide-react"
import { formatDistanceToNow } from "date-fns"
import { ko } from "date-fns/locale"
import type { Post } from "@/lib/types"

/** 카드·히어로 공통 메타 줄: 날짜 · 조회 · 좋아요. */
export default function PostMeta({ post, className = "" }: { post: Post; className?: string }) {
    const created = post.createdAt ? new Date(post.createdAt) : null
    return (
        <div className={`flex items-center gap-3 text-xs text-muted-foreground ${className}`}>
            {created ? <time dateTime={created.toISOString()}>{formatDistanceToNow(created, { addSuffix: true, locale: ko })}</time> : null}
            <span className="flex items-center gap-1"><Eye className="h-3.5 w-3.5" />{(post.views ?? 0).toLocaleString()}</span>
            <span className="flex items-center gap-1"><Heart className="h-3.5 w-3.5" />{post.likes ?? 0}</span>
        </div>
    )
}
