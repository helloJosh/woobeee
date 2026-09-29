"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { useParams, useRouter } from "next/navigation"
import { AlertTriangle, ArrowLeft, Pencil, RefreshCw, Trash2 } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import CommentThread from "@/components/pokemon/comment-thread"
import { OrderProgressSteps } from "@/components/pokemon/order-progress"
import { BankAccountLine, FxDelta, RateLine, StatusBadge } from "@/components/pokemon/pokemon-bits"
import { useAuth } from "@/hooks/use-auth"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import {
    STATUS_FLOW,
    STATUS_LABELS,
    canModifyOrder,
    formatCoins,
    formatInr,
    formatKrw,
    formatRate,
    fxDelta,
    isSettled,
    orderCoins,
    type PokemonOrderDetail,
    type PokemonOrderStatus,
} from "@/lib/pokemon"

const ALL_STATUSES: PokemonOrderStatus[] = [...STATUS_FLOW, "CANCELLED"]

/** 신청서 한 건의 세부 페이지 — 저장 직후 여기로 온다. 댓글도 여기에 있다. */
export default function PokemonOrderDetailPage() {
    const params = useParams<{ orderId: string }>()
    const router = useRouter()
    const { isAuthenticated } = useAuth()

    const orderId = Number(params?.orderId)
    const [detail, setDetail] = useState<PokemonOrderDetail | null>(null)
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)

    const load = useCallback(async () => {
        if (!Number.isInteger(orderId)) {
            setLoadState("failed")
            setError("신청서를 찾을 수 없습니다.")
            return
        }
        try {
            setDetail(await pokemonAPI.getOrder(orderId))
            setLoadState("ready")
            setError(null)
        } catch (caught) {
            setLoadState("failed")
            setError(describeGameApiError(caught, "신청서를 불러오지 못했습니다."))
        }
    }, [orderId])

    useEffect(() => {
        void load()
    }, [load])

    const changeStatus = async (next: PokemonOrderStatus) => {
        try {
            await pokemonAPI.changeStatus(orderId, next)
            await load()
        } catch (caught) {
            setError(describeGameApiError(caught, "상태를 바꾸지 못했습니다."))
        }
    }

    const removeOrder = async () => {
        try {
            await pokemonAPI.deleteOrder(orderId)
            router.replace("/pokemon")
        } catch (caught) {
            setError(describeGameApiError(caught, "신청서를 삭제하지 못했습니다."))
        }
    }

    if (loadState === "loading") {
        return (
            <main className="mx-auto max-w-3xl space-y-4 px-4 py-8">
                <Skeleton className="h-10 w-48" />
                <Skeleton className="h-64 w-full" />
            </main>
        )
    }

    if (loadState === "failed" || detail === null) {
        return (
            <main className="mx-auto max-w-3xl space-y-3 px-4 py-8">
                <Alert variant="destructive">
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription className="flex items-center justify-between gap-4">
                        <span>{error}</span>
                        <Button size="sm" variant="outline" onClick={() => void load()}>
                            <RefreshCw className="mr-1 h-3 w-3" /> 다시 시도
                        </Button>
                    </AlertDescription>
                </Alert>
                <Button asChild variant="ghost" size="sm">
                    <Link href="/pokemon">
                        <ArrowLeft className="mr-1 h-4 w-4" /> 목록으로
                    </Link>
                </Button>
            </main>
        )
    }

    const { order, rate, canManage, bankAccount } = detail
    const delta = fxDelta(order, rate.inrToKrw)

    return (
        <main className="mx-auto max-w-3xl space-y-6 px-4 py-8">
            <header className="space-y-2">
                <Button asChild variant="ghost" size="sm" className="-ml-2">
                    <Link href="/pokemon">
                        <ArrowLeft className="mr-1 h-4 w-4" /> 목록
                    </Link>
                </Button>
                <div className="flex flex-wrap items-center justify-between gap-2">
                    <h1 className="text-2xl font-bold">
                        {order.applicantName}
                        {order.guest && (
                            <span className="ml-2 text-sm font-normal text-muted-foreground">비회원</span>
                        )}
                    </h1>
                    <div className="flex items-center gap-2">
                        {canManage ? (
                            <Select
                                value={order.status}
                                onValueChange={(next) => void changeStatus(next as PokemonOrderStatus)}
                            >
                                <SelectTrigger className="h-9 w-36 text-sm">
                                    <SelectValue />
                                </SelectTrigger>
                                <SelectContent>
                                    {ALL_STATUSES.map((status) => (
                                        <SelectItem key={status} value={status}>
                                            {STATUS_LABELS[status]}
                                        </SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        ) : (
                            <StatusBadge status={order.status} />
                        )}
                        {canModifyOrder(order, canManage) && (
                            <>
                                <Button asChild size="sm" variant="outline">
                                    <Link href={`/pokemon/${orderId}/edit`}>
                                        <Pencil className="mr-1 h-3 w-3" /> 수정
                                    </Link>
                                </Button>
                                <Button
                                    size="icon"
                                    variant="ghost"
                                    aria-label="신청서 삭제"
                                    onClick={() => void removeOrder()}
                                >
                                    <Trash2 className="h-4 w-4" />
                                </Button>
                            </>
                        )}
                    </div>
                </div>
                <div className="text-xs text-muted-foreground">
                    신청 {new Date(order.createdAt).toLocaleString("ko-KR")}
                    {order.depositorName !== null && ` · 입금자 ${order.depositorName}`}
                </div>
            </header>

            {error !== null && (
                <Alert variant="destructive">
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription>{error}</AlertDescription>
                </Alert>
            )}

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">진행도</CardTitle>
                </CardHeader>
                <CardContent>
                    <OrderProgressSteps status={order.status} />
                </CardContent>
            </Card>

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">주문 내역</CardTitle>
                </CardHeader>
                <CardContent className="space-y-3 text-sm">
                    {order.items.map((item, index) => (
                        <div key={`${item.productName}-${index}`} className="flex justify-between">
                            <span>
                                {item.productName} × {item.quantity}
                            </span>
                            <span className="text-muted-foreground">
                                {formatInr(item.unitPriceInr * item.quantity)}
                            </span>
                        </div>
                    ))}
                    {order.extraInr > 0 && (
                        <div className="flex justify-between">
                            <span>
                                직접 입력한 루피
                                <span className="ml-1 text-xs text-muted-foreground">
                                    상품표에 없는 것
                                </span>
                            </span>
                            <span className="text-muted-foreground">{formatInr(order.extraInr)}</span>
                        </div>
                    )}
                    {order.items.length === 0 && order.extraInr === 0 && (
                        <p className="text-muted-foreground">항목이 없습니다.</p>
                    )}
                    <div className="flex justify-between border-t pt-3 font-semibold">
                        <span>받는 포켓코인</span>
                        <span>{formatCoins(orderCoins(order))}</span>
                    </div>
                </CardContent>
            </Card>

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">금액</CardTitle>
                </CardHeader>
                <CardContent className="space-y-4">
                    <div className="space-y-2 text-sm">
                        <div className="flex justify-between">
                            <span className="text-muted-foreground">상품 합계</span>
                            <span>{formatInr(order.totalInr)}</span>
                        </div>
                        <div className="flex justify-between">
                            <span className="text-muted-foreground">
                                환산 (신청 시점 {formatRate(order.quotedRate)})
                            </span>
                            <span>{formatKrw(order.itemsKrw)}</span>
                        </div>
                        <div className="flex justify-between">
                            <span className="text-muted-foreground">기부금</span>
                            <span>{formatKrw(order.donationKrw)}</span>
                        </div>
                        <div className="flex justify-between border-t pt-2 text-base font-semibold">
                            <span>이체할 금액</span>
                            <span>{formatKrw(order.transferKrw)}</span>
                        </div>
                    </div>

                    {(order.status === "ORDERED" || order.status === "PREPARING") && (
                        <BankAccountLine account={bankAccount} />
                    )}

                    <div className="rounded-md border p-3">
                        <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                            <div>
                                <dt className="text-xs text-muted-foreground">환차손익</dt>
                                <dd className="mt-0.5">
                                    <FxDelta delta={delta} settled={isSettled(order)} />
                                </dd>
                            </div>
                            <div>
                                <dt className="text-xs text-muted-foreground">기준</dt>
                                <dd className="mt-0.5 text-sm">
                                    {isSettled(order)
                                        ? `결제 시점 ${formatRate(order.settledRate as number)}`
                                        : `현재 ${formatRate(rate.inrToKrw)}`}
                                </dd>
                            </div>
                        </dl>
                    </div>

                    <RateLine
                        rate={formatRate(rate.inrToKrw)}
                        fetchedAt={rate.fetchedAt}
                        stale={rate.stale}
                    />
                </CardContent>
            </Card>

            {order.memo !== null && (
                <Card>
                    <CardHeader className="pb-3">
                        <CardTitle className="text-base">메모</CardTitle>
                    </CardHeader>
                    <CardContent>
                        <p className="whitespace-pre-wrap text-sm">{order.memo}</p>
                    </CardContent>
                </Card>
            )}

            <Card>
                <CardContent className="pt-6">
                    <CommentThread
                        comments={order.comments}
                        canManage={canManage}
                        loggedIn={isAuthenticated}
                        onCreate={async (authorName, content) => {
                            await pokemonAPI.createComment(orderId, {
                                authorName: authorName.trim() || undefined,
                                content: content.trim(),
                            })
                            await load()
                        }}
                        onDelete={async (commentId) => {
                            try {
                                await pokemonAPI.deleteComment(commentId)
                                await load()
                            } catch (caught) {
                                setError(describeGameApiError(caught, "댓글을 지우지 못했습니다."))
                            }
                        }}
                    />
                </CardContent>
            </Card>
        </main>
    )
}
