"use client"

import { useState } from "react"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { MessageSquare, Trash2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { buildAuthHref } from "@/lib/auth-redirect"
import { describeGameApiError } from "@/lib/game-errors"
import {
    MAX_COMMENT_LENGTH,
    canDeleteComment,
    validateComment,
    type PokemonComment,
} from "@/lib/pokemon"

/**
 * 신청서 세부 페이지의 댓글. 로그인해야 쓸 수 있고 작성자는 회원 닉네임이다.
 * 삭제 버튼을 그릴지는 서버가 준 comment.mine 과 canManage 만 본다 (POKEMON-AC-13).
 */
export default function CommentThread({
    comments,
    canManage,
    loggedIn,
    onCreate,
    onDelete,
}: {
    comments: PokemonComment[]
    canManage: boolean
    loggedIn: boolean
    onCreate: (content: string) => Promise<void>
    onDelete: (commentId: number) => Promise<void>
}) {
    const pathname = usePathname()
    const [content, setContent] = useState("")
    const [error, setError] = useState<string | null>(null)
    const [sending, setSending] = useState(false)

    const send = async () => {
        const problem = validateComment({ content })
        if (problem !== null) {
            setError(problem)
            return
        }

        setError(null)
        setSending(true)
        try {
            await onCreate(content)
            setContent("")
        } catch (caught) {
            setError(describeGameApiError(caught, "댓글을 남기지 못했습니다."))
        } finally {
            setSending(false)
        }
    }

    return (
        <div className="space-y-3">
            <div className="flex items-center gap-1 text-sm font-medium">
                <MessageSquare className="h-4 w-4" /> 댓글 {comments.length}
            </div>

            {comments.length === 0 && (
                <p className="text-sm text-muted-foreground">아직 댓글이 없습니다.</p>
            )}

            {comments.map((comment) => (
                <div key={comment.id} className="flex items-start justify-between gap-2 border-b pb-2 text-sm">
                    <div>
                        <span className="font-medium">{comment.authorName}</span>
                        {comment.guest && (
                            <span className="ml-1 text-xs text-muted-foreground">비회원</span>
                        )}
                        <span className="ml-2 text-xs text-muted-foreground">
                            {new Date(comment.createdAt).toLocaleString("ko-KR")}
                        </span>
                        <p className="whitespace-pre-wrap">{comment.content}</p>
                    </div>
                    {canDeleteComment(comment, canManage) && (
                        <Button
                            size="icon"
                            variant="ghost"
                            aria-label="댓글 삭제"
                            onClick={() => void onDelete(comment.id)}
                        >
                            <Trash2 className="h-3 w-3" />
                        </Button>
                    )}
                </div>
            ))}

            {!loggedIn ? (
                <p className="text-sm text-muted-foreground">
                    댓글을 쓰려면{" "}
                    <Link href={buildAuthHref("/login", pathname)} className="underline">
                        로그인
                    </Link>
                    이 필요합니다.
                </p>
            ) : (
            <div className="flex flex-wrap items-start gap-2">
                <Input
                    className="h-9 flex-1"
                    value={content}
                    onChange={(event) => setContent(event.target.value)}
                    placeholder="댓글 남기기"
                    maxLength={MAX_COMMENT_LENGTH}
                />
                <Button size="sm" onClick={() => void send()} disabled={sending}>
                    {sending ? "…" : "등록"}
                </Button>
            </div>
            )}

            {error !== null && <p className="text-xs text-red-600 dark:text-red-400">{error}</p>}
        </div>
    )
}
