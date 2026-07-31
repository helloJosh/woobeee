"use client"

import { useState } from "react"
import { formatDistanceToNow } from "date-fns"
import { ko } from "date-fns/locale"
import {MessageCircle, Reply, Loader2, Trash2, Edit3, Edit2, Edit} from "lucide-react"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader } from "@/components/ui/card"
import { Textarea } from "@/components/ui/textarea"
import { Input } from "@/components/ui/input"
import type { Comment } from "@/lib/types"
import { useComments } from "@/hooks/use-comments"

interface CommentSectionProps {
  postId: number
  userId?: string
}

export default function CommentSection({ postId, userId }: CommentSectionProps) {
  const { comments, loading, error, addComment, addReply, deleteComment } = useComments(postId, userId)

  const [newComment, setNewComment] = useState("")
  //const [newAuthor, setNewAuthor] = useState("")
  const [replyingTo, setReplyingTo] = useState<number | null>(null)
  const [replyContent, setReplyContent] = useState("")
  const [replyAuthor, setReplyAuthor] = useState("")

  const handleAddComment = async () => {
    if (!newComment.trim()) return
    await addComment(newComment.trim())
    setNewComment("")
    //setNewAuthor("")
  }

  const handleAddReply = async (parentId: number) => {
    //if (!replyContent.trim() || !replyAuthor.trim()) return
    if (!replyContent.trim()) return
    await addReply(Number(parentId) , replyContent.trim())
    setReplyContent("")
    //setReplyAuthor("")
    setReplyingTo(null)
  }

  const renderComment = (comment: Comment, isReply = false) => (
      <div key={comment.id} className={`${isReply ? "ml-8 mt-4" : ""}`}>
        <Card className={isReply ? "bg-muted/50" : ""}>
          <CardHeader className="pb-3">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <span className="font-medium">{comment.author}</span>
                {isReply && <Reply className="h-3 w-3 text-muted-foreground" />}
              </div>
              <div className="flex items-center gap-1">
              <span className="text-xs text-muted-foreground mr-2">
                {formatDistanceToNow(comment.createdAt, { addSuffix: true, locale: ko })}
              </span>
                {comment.isEditable && (
                    <Button
                        variant="ghost"
                        size="icon"
                        className="h-7 w-7 text-muted-foreground"
                        onClick={() => deleteComment(Number(comment.id))}
                        title="수정"
                    >
                      <Edit2 className="h-4 w-4" />
                    </Button>
                )}
                <Button
                    variant="ghost"
                    size="icon"
                    className="h-7 w-7 text-muted-foreground"
                    onClick={() => deleteComment(Number(comment.id))}
                    title="삭제"
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
              </div>
            </div>
          </CardHeader>
          <CardContent className="pt-0">
            <p className="text-sm whitespace-pre-wrap">{comment.content}</p>
            {!isReply && (
                <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => setReplyingTo(comment.id)}
                    className="mt-2 h-8 px-2 text-xs"
                >
                  <Reply className="h-3 w-3 mr-1" />
                  답글
                </Button>
            )}
          </CardContent>
        </Card>

        {replyingTo === comment.id && (
            <Card className="ml-8 mt-2">
              <CardContent className="pt-4">
                <div className="space-y-3">
                  {/*<Input*/}
                  {/*    placeholder="익명"*/}
                  {/*    value={replyAuthor}*/}
                  {/*    onChange={(e) => setReplyAuthor(e.target.value)}*/}
                  {/*    className="h-8"*/}
                  {/*/>*/}
                  <Textarea
                      placeholder="답글을 입력하세요..."
                      value={replyContent}
                      onChange={(e) => setReplyContent(e.target.value)}
                      className="min-h-[80px] resize-none"
                  />
                  <div className="flex gap-2">
                    <Button
                        size="sm"
                        onClick={() => handleAddReply(comment.id)}
                        disabled={
                            !localStorage.getItem("accessToken") // ← 여기!
                        }
                    >
                      답글 작성
                    </Button>
                    {!localStorage.getItem("accessToken") && (
                        <p className="text-xs text-muted-foreground">로그인 후 답글을 작성할 수 있습니다.</p>
                    )}
                    <Button variant="outline" size="sm" onClick={() => setReplyingTo(null)}>
                      취소
                    </Button>
                  </div>
                </div>
              </CardContent>
            </Card>
        )}

        {comment.replies?.map((reply) => renderComment(reply, true))}
      </div>
  )

  return (
      <div className="space-y-6">
        <Card>
          <CardHeader className="flex flex-row items-center justify-between">
            <h3 className="font-semibold flex items-center gap-2">
              <MessageCircle className="h-5 w-5" />
              댓글 {comments.length}개
            </h3>

            {loading && (
                <span className="text-xs text-muted-foreground flex items-center gap-1">
              <Loader2 className="h-3 w-3 animate-spin" />
              로딩 중…
            </span>
            )}
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {/*<Input placeholder="익명" value={newAuthor} onChange={(e) => setNewAuthor(e.target.value)} />*/}
              <Textarea
                  placeholder="댓글을 입력하세요..."
                  value={newComment}
                  onChange={(e) => setNewComment(e.target.value)}
                  className="min-h-[100px] resize-none"
              />
              <Button
                  onClick={handleAddComment}
                  disabled={
                      !localStorage.getItem("accessToken") // ← 추가
                  }
              >
                댓글 작성
              </Button>
              {!localStorage.getItem("accessToken") && (
                  <p className="text-xs text-muted-foreground mt-2">로그인 후 댓글을 작성할 수 있습니다.</p>
              )}

              {error && <p className="text-sm text-destructive">{error}</p>}
            </div>
          </CardContent>
        </Card>

        <div className="space-y-4">
          {comments.map((comment) => renderComment(comment))}
        </div>

        {!loading && comments.length === 0 && (
            <div className="text-center py-8">
              <p className="text-muted-foreground">첫 번째 댓글을 작성해보세요!</p>
            </div>
        )}
      </div>
  )
}
