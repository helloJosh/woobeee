"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { useParams, useRouter, useSearchParams } from "next/navigation"
import { AlertTriangle, ArrowLeft, Plus, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import OrderRows from "@/components/pokemon/order-rows"
import Pager from "@/components/pokemon/pager"
import RoundBoard from "@/components/pokemon/round-board"
import RoundTabs from "@/components/pokemon/round-tabs"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import {
    ORDER_STATUS_LABELS, formatKrw, paginate,
    type PokemonHost, type PokemonRoundBoard, type PokemonRoundStatus,
} from "@/lib/pokemon"

/** 탭에서 "전체" 를 고른 상태. 차수 번호와 섞이지 않게 문자열로 둔다. */
const ALL = "all"

/**
 * {@code /pokemon/{handle}} — 한 주최자의 방.
 *
 * <p>차수가 탭으로 나뉜다. "전체" 는 모든 차수의 신청서를 모아 보여주고, 차수 탭을 고르면
 * 그 차수 화면이 <b>그 자리에</b> 뜬다 — 옮겨 다니지 않아도 된다. 고른 탭은 주소
 * ({@code ?round=2})에 남아 링크를 그대로 공유할 수 있다.
 */
export default function PokemonHostPage() {
    const params = useParams<{ handle: string }>()
    const searchParams = useSearchParams()
    const router = useRouter()
    const handle = params?.handle ?? ""
    const selected = searchParams?.get("round") ?? ALL

    const [host, setHost] = useState<PokemonHost | null>(null)
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)
    const [page, setPage] = useState(1)

    // 고른 차수의 본문. 탭을 옮길 때마다 받아 오고, 받아 둔 것은 다시 부르지 않는다.
    const [boards, setBoards] = useState<Record<number, PokemonRoundBoard>>({})
    const [boardLoading, setBoardLoading] = useState(false)

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

    const sequence = selected === ALL ? null : Number(selected)

    useEffect(() => {
        if (sequence === null || !Number.isInteger(sequence) || boards[sequence] !== undefined) {
            return
        }
        let cancelled = false
        setBoardLoading(true)
        pokemonAPI.getRoundBoard(handle, sequence)
            .then((board) => {
                if (!cancelled) setBoards((current) => ({ ...current, [sequence]: board }))
            })
            .catch((caught) => {
                if (!cancelled) setError(describeGameApiError(caught, "차수를 불러오지 못했습니다."))
            })
            .finally(() => {
                if (!cancelled) setBoardLoading(false)
            })
        return () => {
            cancelled = true
        }
    }, [handle, sequence, boards])

    const select = (next: string) => {
        setPage(1)
        const query = next === ALL ? "" : `?round=${next}`
        router.replace(`/pokemon/${handle}${query}`, { scroll: false })
    }

    /** 차수마다 통화가 다를 수 있어 외화는 더하지 않는다. 원화(이체액)만 모은다. */
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
                    <Link href="/pokemon"><ArrowLeft className="mr-1 h-4 w-4" /> 전체 목록</Link>
                </Button>
            </main>
        )
    }

    const board = sequence === null ? null : boards[sequence]
    const pagedAll = paginate(host.orders, page)

    return (
        <main className="mx-auto max-w-4xl space-y-6 px-4 py-8">
            <header className="space-y-2">
                <Button asChild variant="ghost" size="sm" className="-ml-2">
                    <Link href="/pokemon"><ArrowLeft className="mr-1 h-4 w-4" /> 전체 목록</Link>
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

            <RoundTabs
                rounds={host.rounds}
                selected={selected}
                totalOrders={host.orders.length}
                onSelect={select}
            />

            {error !== null && (
                <Alert variant="destructive">
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription>{error}</AlertDescription>
                </Alert>
            )}

            {sequence !== null ? (
                boardLoading || board === undefined || board === null ? (
                    <Skeleton className="h-64 w-full" />
                ) : (
                    <RoundBoard
                        board={board}
                        onChangeStatus={(next: PokemonRoundStatus) =>
                            void pokemonAPI.changeRoundStatus(board.round.id, next)
                                .then(async () => {
                                    // 바뀐 차수만 다시 받고, 모아 보기의 건수도 함께 맞춘다.
                                    setBoards((c) => {
                                        const { [sequence]: _dropped, ...rest } = c
                                        return rest
                                    })
                                    await load()
                                })
                                .catch((c) => setError(describeGameApiError(c, "상태를 바꾸지 못했습니다.")))
                        }
                    />
                )
            ) : (
                <Card>
                    <CardHeader className="pb-3">
                        <CardTitle className="text-base">전체 신청서 {host.orders.length}건</CardTitle>
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
                                <OrderRows orders={pagedAll.items} rounds={host.rounds} showRound />
                                <Pager page={pagedAll.page} totalPages={pagedAll.totalPages}
                                       total={host.orders.length} onChange={setPage} />
                            </>
                        )}
                    </CardContent>
                </Card>
            )}
        </main>
    )
}
