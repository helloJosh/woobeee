
import {useMemo} from "react"
import { formatDistanceToNow } from "date-fns"
import { ko } from "date-fns/locale"
import { ArrowLeft, Eye, Heart, MessageCircle, Share2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { useLike } from "@/hooks/use-like"
import {GetPostResponse} from "@/lib/types";


export function LikeBar({ post }: { post: GetPostResponse }) {
    const { likes, isLiked, toggleLike } = useLike(
        post.id,
        post.likes ?? 0,
        post.isLiked ?? false
    )
    return (
        <Button
            variant={isLiked ? "default" : "outline"}
            onClick={toggleLike}
            className="flex items-center gap-2"
        >
        <Heart className={`h-4 w-4 ${isLiked ? "fill-current" : ""}`} />
        좋아요 {likes}
        </Button>
    )
}
