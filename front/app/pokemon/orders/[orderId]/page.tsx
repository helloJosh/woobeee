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
import { OrderProgressSteps } from "@/components/pokemon/progress-steps"
import {
    BankAccountLine, FxDelta, OrderStatusBadge, RateLine,
} from "@/components/pokemon/pokemon-bits"
import { useAuth } from "@/hooks/use-auth"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import {
    ORDER_STATUS_FLOW, ORDER_STATUS_LABELS, canModifyOrder, formatAmount, formatCoins,
    formatKrw, formatRate, fxDelta, isRoundSettled, orderCoins, roundTitle,
    type PokemonOrderDetail, type PokemonOrderStatus,
} from "@/lib/pokemon"

const ALL_ORDER_STATUSES: PokemonOrderStatus[] = [...ORDER_STATUS_FLOW, "CANCELLED"]

/** 신청서 한 건. 저장 직후 여기로 온다. 댓글도 여기에 있다. */
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
                    <Link href="/pokemon"><ArrowLeft className="mr-1 h-4 w-4" /> 전체 목록</Link>
                </Button>
            </main>
        )
    }

    const { order, round, rate, canManage } = detail
    const delta = fxDelta(order, round, rate.toKrw)
    const backToRound = `/pokemon/${round.hostHandle}/${round.sequence}`

    return (
        <main className="mx-auto max-w-3xl space-y-6 px-4 py-8">
            <header className="space-y-2">
                <Button asChild variant="ghost" size="sm" className="-ml-2">
                    <Link href={backToRound}>
                        <ArrowLeft className="mr-1 h-4 w-4" /> {roundTitle(round)}
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
                                onValueChange={(next) =>
                                    void pokemonAPI.changeStatus(orderId, next as PokemonOrderStatus)
                                        .then(() => load())
                                        .catch((c) => setError(describeGameApiError(c, "상태를 바꾸지 못했습니다.")))
                                }
                            >
                                <SelectTrigger className="h-9 w-32 text-sm"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                    {ALL_ORDER_STATUSES.map((s) => (
                                        <SelectItem key={s} value={s}>{ORDER_STATUS_LABELS[s]}</SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        ) : (
                            <OrderStatusBadge status={order.status} />
                        )}
                        {canModifyOrder(order, canManage) && (
                            <>
                                <Button asChild size="sm" variant="outline">
                                    <Link href={`/pokemon/orders/${orderId}/edit`}>
                                        <Pencil className="mr-1 h-3 w-3" /> 수정
                                    </Link>
                                </Button>
                                <Button
                                    size="icon" variant="ghost" aria-label="신청서 삭제"
                                    onClick={() =>
                                        void pokemonAPI.deleteOrder(orderId)
                                            .then(() => router.replace(backToRound))
                                            .catch((c) => setError(describeGameApiError(c, "삭제하지 못했습니다.")))
                                    }
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
                <CardHeader className="pb-3"><CardTitle className="text-base">진행도</CardTitle></CardHeader>
                <CardContent><OrderProgressSteps status={order.status} /></CardContent>
            </Card>

            <Card>
                <CardHeader className="pb-3"><CardTitle className="text-base">주문 내역</CardTitle></CardHeader>
                <CardContent className="space-y-3 text-sm">
                    {order.items.map((item, index) => (
                        <div key={`${item.productName}-${index}`} className="flex justify-between">
                            <span>{item.productName} × {item.quantity}</span>
                            <span className="text-muted-foreground">
                                {formatAmount(item.unitPrice * item.quantity, round.currency)}
                            </span>
                        </div>
                    ))}
                    {order.extraAmount > 0 && (
                        <div className="flex justify-between">
                            <span>
                                직접 입력한 금액
                                <span className="ml-1 text-xs text-muted-foreground">상품표에 없는 것</span>
                            </span>
                            <span className="text-muted-foreground">
                                {formatAmount(order.extraAmount, round.currency)}
                            </span>
                        </div>
                    )}
                    <div className="flex justify-between border-t pt-3 font-semibold">
                        <span>받는 포켓코인</span>
                        <span>{formatCoins(orderCoins(order))}</span>
                    </div>
                </CardContent>
            </Card>

            <Card>
                <CardHeader className="pb-3"><CardTitle className="text-base">금액</CardTitle></CardHeader>
                <CardContent className="space-y-4">
                    <div className="space-y-2 text-sm">
                        <div className="flex justify-between">
                            <span className="text-muted-foreground">상품 합계</span>
                            <span>{formatAmount(order.totalAmount, round.currency)}</span>
                        </div>
                        <div className="flex justify-between">
                            <span className="text-muted-foreground">
                                환산 ({formatRate(order.quotedRate, round.currency)})
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

                    {order.status === "ORDERED" && <BankAccountLine account={round.bankAccount} />}

                    <div className="rounded-md border p-3">
                        <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                            <div>
                                <dt className="text-xs text-muted-foreground">환차손익</dt>
                                <dd className="mt-0.5">
                                    <FxDelta delta={delta} settled={isRoundSettled(round)} />
                                </dd>
                            </div>
                            <div>
                                <dt className="text-xs text-muted-foreground">기준</dt>
                                <dd className="mt-0.5 text-sm">
                                    {isRoundSettled(round)
                                        ? `차수 결제 시점 ${formatRate(round.settledRate as number, round.currency)}`
                                        : `현재 ${formatRate(rate.toKrw, round.currency)}`}
                                </dd>
                            </div>
                        </dl>
                    </div>

                    <RateLine
                        rate={formatRate(rate.toKrw, round.currency)}
                        fetchedAt={rate.fetchedAt}
                        stale={rate.stale}
                    />
                </CardContent>
            </Card>

            {order.memo !== null && (
                <Card>
                    <CardHeader className="pb-3"><CardTitle className="text-base">메모</CardTitle></CardHeader>
                    <CardContent><p className="whitespace-pre-wrap text-sm">{order.memo}</p></CardContent>
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
