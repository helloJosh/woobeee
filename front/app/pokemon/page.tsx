"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { AlertTriangle, Coins, MessageSquare, Plus, RefreshCw, Settings } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { OrderProgressBar } from "@/components/pokemon/order-progress"
import { BankAccountLine, FxDelta, RateLine, Stat } from "@/components/pokemon/pokemon-bits"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import {
    STATUS_FLOW,
    STATUS_LABELS,
    formatCoins,
    formatInr,
    formatKrw,
    formatRate,
    fxDelta,
    isSettled,
    orderCoins,
    summarize,
    type PokemonBoard,
    type PokemonOrderStatus,
} from "@/lib/pokemon"

const ALL_STATUSES: PokemonOrderStatus[] = [...STATUS_FLOW, "CANCELLED"]

/** 목록 화면. 신청은 /pokemon/new, 한 건의 자세한 내용과 댓글은 /pokemon/{id} 로 간다. */
export default function PokemonListPage() {
    const [board, setBoard] = useState<PokemonBoard | null>(null)
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [loadError, setLoadError] = useState<string | null>(null)

    const load = useCallback(async () => {
        try {
            setBoard(await pokemonAPI.getBoard())
            setLoadState("ready")
            setLoadError(null)
        } catch (error) {
            setLoadState("failed")
            setLoadError(describeGameApiError(error, "신청 현황을 불러오지 못했습니다."))
        }
    }, [])

    useEffect(() => {
        void load()
    }, [load])

    const rate = board?.rate.inrToKrw ?? 0
    const orders = board?.orders ?? []
    const summary = useMemo(() => summarize(orders), [orders])

    if (loadState === "loading") {
        return (
            <main className="mx-auto max-w-5xl space-y-4 px-4 py-8">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-40 w-full" />
                <Skeleton className="h-64 w-full" />
            </main>
        )
    }

    if (loadState === "failed" || board === null) {
        return (
            <main className="mx-auto max-w-5xl px-4 py-8">
                <Alert variant="destructive">
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription className="flex items-center justify-between gap-4">
                        <span>{loadError}</span>
                        <Button size="sm" variant="outline" onClick={() => void load()}>
                            <RefreshCw className="mr-1 h-3 w-3" /> 다시 시도
                        </Button>
                    </AlertDescription>
                </Alert>
            </main>
        )
    }

    return (
        <main className="mx-auto max-w-5xl space-y-6 px-4 py-8">
            <header className="flex flex-wrap items-start justify-between gap-3">
                <div className="space-y-2">
                    <h1 className="flex items-center gap-2 text-2xl font-bold">
                        <Coins className="h-6 w-6" /> 포켓코인 공동구매
                    </h1>
                    <RateLine
                        rate={formatRate(rate)}
                        fetchedAt={board.rate.fetchedAt}
                        stale={board.rate.stale}
                    />
                    <BankAccountLine account={board.bankAccount} />
                </div>
                <div className="flex items-center gap-2">
                    {board.canManage && (
                        <Button asChild variant="outline">
                            <Link href="/pokemon/products">
                                <Settings className="mr-1 h-4 w-4" /> 상품 관리
                            </Link>
                        </Button>
                    )}
                    <Button asChild>
                        <Link href="/pokemon/new">
                            <Plus className="mr-1 h-4 w-4" /> 신청서 작성
                        </Link>
                    </Button>
                </div>
            </header>

            {board.canManage && (
                <Card>
                    <CardHeader className="pb-3">
                        <CardTitle className="text-base">운영자 — 처리할 금액</CardTitle>
                    </CardHeader>
                    <CardContent className="space-y-3">
                        <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-3 lg:grid-cols-5">
                            <Stat
                                label={`배달 완료 전 ${summary.inFlightCount}건 · 루피`}
                                value={formatInr(summary.inFlightInr)}
                            />
                            <Stat
                                label="기부금 뺀 금액"
                                value={formatKrw(summary.inFlightItemsKrw)}
                            />
                            <Stat
                                label="이체 합계 (기부금 포함)"
                                value={formatKrw(summary.inFlightKrw)}
                            />
                            <Stat
                                label={`미입금 ${summary.awaitingDeposit.length}건`}
                                value={formatKrw(summary.outstandingKrw)}
                            />
                            <Stat label="총 포켓코인" value={formatCoins(summary.totalCoins)} />
                        </dl>
                        {summary.awaitingDeposit.length > 0 && (
                            <div className="border-t pt-3 text-sm">
                                <span className="font-medium">미입금 </span>
                                <span className="text-muted-foreground">
                                    {summary.awaitingDeposit
                                        .map((order) => order.applicantName)
                                        .join(", ")}
                                </span>
                            </div>
                        )}
                        <p className="text-xs text-muted-foreground">취소된 신청서는 빠집니다.</p>
                    </CardContent>
                </Card>
            )}

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">신청서 {orders.length}건</CardTitle>
                    <div className="flex flex-wrap gap-2 pt-1">
                        {ALL_STATUSES.map((status) => (
                            <Badge key={status} variant="outline">
                                {STATUS_LABELS[status]} {summary.statusCounts[status]}
                            </Badge>
                        ))}
                    </div>
                </CardHeader>
                <CardContent className="space-y-2">
                    {orders.length === 0 ? (
                        <div className="space-y-3 py-8 text-center">
                            <p className="text-sm text-muted-foreground">아직 신청서가 없습니다.</p>
                            <Button asChild variant="outline" size="sm">
                                <Link href="/pokemon/new">첫 신청서 작성하기</Link>
                            </Button>
                        </div>
                    ) : (
                        orders.map((order) => (
                            <Link
                                key={order.id}
                                href={`/pokemon/${order.id}`}
                                className={`block rounded-md border p-3 transition-colors hover:bg-accent ${
                                    order.status === "CANCELLED" ? "opacity-50" : ""
                                }`}
                            >
                                <div className="flex flex-wrap items-start justify-between gap-2">
                                    <div>
                                        <div className="font-medium">
                                            {order.applicantName}
                                            {order.guest && (
                                                <span className="ml-1 text-xs text-muted-foreground">
                                                    비회원
                                                </span>
                                            )}
                                        </div>
                                        <div className="text-xs text-muted-foreground">
                                            {new Date(order.createdAt).toLocaleString("ko-KR")}
                                            {order.comments.length > 0 && (
                                                <span className="ml-2 inline-flex items-center gap-1">
                                                    <MessageSquare className="h-3 w-3" />
                                                    {order.comments.length}
                                                </span>
                                            )}
                                        </div>
                                    </div>
                                </div>

                                <div className="mt-3">
                                    <OrderProgressBar status={order.status} />
                                </div>

                                <dl className="mt-3 grid grid-cols-2 gap-2 text-sm sm:grid-cols-4">
                                    <div>
                                        <dt className="text-xs text-muted-foreground">받는 포켓코인</dt>
                                        <dd className="font-semibold">{formatCoins(orderCoins(order))}</dd>
                                    </div>
                                    <div>
                                        <dt className="text-xs text-muted-foreground">상품 합계</dt>
                                        <dd>{formatInr(order.totalInr)}</dd>
                                    </div>
                                    <div>
                                        <dt className="text-xs text-muted-foreground">이체할 금액</dt>
                                        <dd className="font-semibold">{formatKrw(order.transferKrw)}</dd>
                                    </div>
                                    <div>
                                        <dt className="text-xs text-muted-foreground">환차손익</dt>
                                        <dd>
                                            <FxDelta
                                                delta={fxDelta(order, rate)}
                                                settled={isSettled(order)}
                                            />
                                        </dd>
                                    </div>
                                </dl>
                            </Link>
                        ))
                    )}
                </CardContent>
            </Card>
        </main>
    )
}
