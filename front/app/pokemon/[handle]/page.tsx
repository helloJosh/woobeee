"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { useParams } from "next/navigation"
import { AlertTriangle, ArrowLeft, Plus, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import OrderRows from "@/components/pokemon/order-rows"
import Pager from "@/components/pokemon/pager"
import RoundList from "@/components/pokemon/round-list"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import { ORDER_STATUS_LABELS, formatKrw, paginate, type PokemonHost } from "@/lib/pokemon"

/** /pokemon/{handle} — 한 주최자가 연 차수들. */
export default function PokemonHostPage() {
    const params = useParams<{ handle: string }>()
    const handle = params?.handle ?? ""

    const [host, setHost] = useState<PokemonHost | null>(null)
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)
    const [page, setPage] = useState(1)

    const load = useCallback(async () => {
        try {
            setHost(await pokemonAPI.getHost(handle))
            setLoadState("ready")
            setError(null)
        } catch (caught) {
            setLoadState("failed")
            setError(describeGameApiError(caught, "주최자를 찾지 못했습니다."))
        }
    }, [handle])

    useEffect(() => {
        void load()
    }, [load])

    // 여러 차수가 섞이면 통화가 달라 외화 합계는 의미가 없다. 원화(이체액)만 모은다.
    const { statusCounts, transferTotal } = useMemo(() => {
        const counts = { ORDERED: 0, DEPOSIT_CONFIRMED: 0, DELIVERED: 0, CANCELLED: 0 }
        let total = 0
        for (const order of host?.orders ?? []) {
            counts[order.status] += 1
            if (order.status !== "CANCELLED") total += order.transferKrw
        }
        return { statusCounts: counts, transferTotal: total }
    }, [host])

    if (loadState === "loading") {
        return (
            <main className="mx-auto max-w-4xl space-y-4 px-4 py-8">
                <Skeleton className="h-10 w-48" />
                <Skeleton className="h-48 w-full" />
            </main>
        )
    }

    if (loadState === "failed" || host === null) {
        return (
            <main className="mx-auto max-w-4xl space-y-3 px-4 py-8">
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
                        <ArrowLeft className="mr-1 h-4 w-4" /> 전체 목록
                    </Link>
                </Button>
            </main>
        )
    }

    return (
        <main className="mx-auto max-w-4xl space-y-6 px-4 py-8">
            <header className="space-y-2">
                <Button asChild variant="ghost" size="sm" className="-ml-2">
                    <Link href="/pokemon">
                        <ArrowLeft className="mr-1 h-4 w-4" /> 전체 목록
                    </Link>
                </Button>
                <div className="flex flex-wrap items-center justify-between gap-2">
                    <div>
                        <h1 className="text-2xl font-bold">{host.name}의 공동구매</h1>
                        <p className="text-xs text-muted-foreground">/pokemon/{host.handle}</p>
                    </div>
                    {host.isMe && (
                        <Button asChild>
                            <Link href="/pokemon/new">
                                <Plus className="mr-1 h-4 w-4" /> 새 차수 열기
                            </Link>
                        </Button>
                    )}
                </div>
            </header>

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">차수 {host.rounds.length}개</CardTitle>
                </CardHeader>
                <CardContent>
                    <RoundList rounds={host.rounds} emptyMessage="아직 연 차수가 없습니다." />
                </CardContent>
            </Card>

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">
                        전체 신청서 {host.orders.length}건
                    </CardTitle>
                    <p className="text-xs text-muted-foreground">
                        모든 차수의 신청서를 최신순으로 모았습니다. 금액은 각 차수 통화로 찍힙니다.
                    </p>
                    <div className="flex flex-wrap gap-2 pt-1">
                        {(Object.keys(ORDER_STATUS_LABELS) as (keyof typeof ORDER_STATUS_LABELS)[])
                            .map((s) => (
                                <Badge key={s} variant="outline">
                                    {ORDER_STATUS_LABELS[s]} {statusCounts[s]}
                                </Badge>
                            ))}
                        <Badge variant="secondary">받을 금액 {formatKrw(transferTotal)}</Badge>
                    </div>
                </CardHeader>
                <CardContent className="space-y-2">
                    {host.orders.length === 0 ? (
                        <p className="py-8 text-center text-sm text-muted-foreground">
                            아직 신청서가 없습니다.
                        </p>
                    ) : (
                        <>
                            <OrderRows
                                orders={paginate(host.orders, page).items}
                                rounds={host.rounds}
                                showRound
                            />
                            <Pager
                                page={paginate(host.orders, page).page}
                                totalPages={paginate(host.orders, page).totalPages}
                                total={host.orders.length}
                                onChange={setPage}
                            />
                        </>
                    )}
                </CardContent>
            </Card>
        </main>
    )
}
