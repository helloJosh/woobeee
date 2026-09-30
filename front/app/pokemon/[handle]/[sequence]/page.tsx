"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { useParams, useRouter } from "next/navigation"
import { AlertTriangle, ArrowLeft, Plus, RefreshCw, Settings } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Skeleton } from "@/components/ui/skeleton"
import OrderRows from "@/components/pokemon/order-rows"
import Pager from "@/components/pokemon/pager"
import { OrderProgressBar, RoundProgressSteps } from "@/components/pokemon/progress-steps"
import RoundTabs from "@/components/pokemon/round-tabs"
import {
    BankAccountLine, FxDelta, OrderStatusBadge, RateLine, Stat,
} from "@/components/pokemon/pokemon-bits"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import {
    ORDER_STATUS_LABELS, RATE_MODE_LABELS, ROUND_STATUS_FLOW, ROUND_STATUS_LABELS,
    acceptsOrders, formatAmount, formatCoins, formatKrw, formatRate, formatSignedKrw,
    fxDelta, isRoundSettled, orderCoins, paginate, rateFor, roundTitle, summarizeRound,
    type PokemonHost, type PokemonRoundBoard, type PokemonRoundStatus,
} from "@/lib/pokemon"

const ALL_ROUND_STATUSES: PokemonRoundStatus[] = [...ROUND_STATUS_FLOW, "CANCELLED"]

/** /pokemon/{handle}/{sequence} — 차수 하나의 모든 것. */
export default function PokemonRoundPage() {
    const params = useParams<{ handle: string; sequence: string }>()
    const router = useRouter()
    const handle = params?.handle ?? ""
    const sequence = Number(params?.sequence)

    const [board, setBoard] = useState<PokemonRoundBoard | null>(null)
    // 폴더 카드에 쓸 형제 차수들. 차수 조회는 한 건만 주므로 주최자 조회를 함께 한다.
    const [siblings, setSiblings] = useState<PokemonHost | null>(null)
    const [page, setPage] = useState(1)
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)

    const load = useCallback(async () => {
        if (!Number.isInteger(sequence)) {
            setLoadState("failed")
            setError("공동구매를 찾을 수 없습니다.")
            return
        }
        try {
            const [loadedBoard, loadedHost] = await Promise.all([
                pokemonAPI.getRoundBoard(handle, sequence),
                pokemonAPI.getHost(handle),
            ])
            setBoard(loadedBoard)
            setSiblings(loadedHost)
            setLoadState("ready")
            setError(null)
        } catch (caught) {
            setLoadState("failed")
            setError(describeGameApiError(caught, "공동구매를 불러오지 못했습니다."))
        }
    }, [handle, sequence])

    useEffect(() => {
        void load()
    }, [load])

    // 다른 차수로 옮기면 목록도 처음부터 본다.
    useEffect(() => {
        setPage(1)
    }, [sequence])

    const summary = useMemo(
        () => board === null
            ? null
            : summarizeRound(board.orders, board.round, board.currentRate.toKrw),
        [board],
    )

    if (loadState === "loading") {
        return (
            <main className="mx-auto max-w-4xl space-y-4 px-4 py-8">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-40 w-full" />
                <Skeleton className="h-64 w-full" />
            </main>
        )
    }

    if (loadState === "failed" || board === null || summary === null) {
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

    const { round, currentRate, orders } = board
    const applyRate = rateFor(round, currentRate.toKrw)

    const changeRoundStatus = (next: PokemonRoundStatus) =>
        pokemonAPI.changeRoundStatus(round.id, next)
            .then(() => load())
            .catch((caught) => setError(describeGameApiError(caught, "상태를 바꾸지 못했습니다.")))

    return (
        <main className="mx-auto max-w-4xl space-y-6 px-4 py-8">
            <header className="space-y-2">
                <Button asChild variant="ghost" size="sm" className="-ml-2">
                    <Link href={`/pokemon/${round.hostHandle}`}>
                        <ArrowLeft className="mr-1 h-4 w-4" /> {round.hostName}의 공동구매
                    </Link>
                </Button>
                <div className="flex flex-wrap items-center justify-between gap-2">
                    <h1 className="text-2xl font-bold">{roundTitle(round)}</h1>
                    <div className="flex items-center gap-2">
                        {round.canManage ? (
                            <Select value={round.status}
                                    onValueChange={(v) => void changeRoundStatus(v as PokemonRoundStatus)}>
                                <SelectTrigger className="h-9 w-32 text-sm"><SelectValue /></SelectTrigger>
                                <SelectContent>
                                    {ALL_ROUND_STATUSES.map((s) => (
                                        <SelectItem key={s} value={s}>{ROUND_STATUS_LABELS[s]}</SelectItem>
                                    ))}
                                </SelectContent>
                            </Select>
                        ) : (
                            <Badge variant={round.status === "OPEN" ? "default" : "secondary"}>
                                {ROUND_STATUS_LABELS[round.status]}
                            </Badge>
                        )}
                        {round.canManage && (
                            <Button asChild size="sm" variant="outline">
                                <Link href={`/pokemon/${round.hostHandle}/${round.sequence}/manage`}>
                                    <Settings className="mr-1 h-4 w-4" /> 차수 관리
                                </Link>
                            </Button>
                        )}
                        {acceptsOrders(round) && (
                            <Button asChild>
                                <Link href={`/pokemon/${round.hostHandle}/${round.sequence}/new`}>
                                    <Plus className="mr-1 h-4 w-4" /> 신청하기
                                </Link>
                            </Button>
                        )}
                    </div>
                </div>
                <RateLine
                    rate={formatRate(applyRate, round.currency)}
                    fetchedAt={round.quotedAt}
                    stale={currentRate.stale}
                    note={RATE_MODE_LABELS[round.rateMode]}
                />
                <BankAccountLine account={round.bankAccount} />
                {round.deadline !== null && (
                    <p className="text-xs text-muted-foreground">
                        마감 {new Date(round.deadline).toLocaleDateString("ko-KR")}
                    </p>
                )}
                {round.memo !== null && (
                    <p className="whitespace-pre-wrap rounded bg-muted p-2 text-xs">{round.memo}</p>
                )}
            </header>

            {siblings !== null && siblings.rounds.length > 1 && (
                <RoundTabs rounds={siblings.rounds} currentId={round.id} />
            )}

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
                <CardContent><RoundProgressSteps status={round.status} /></CardContent>
            </Card>

            {round.canManage && (
                <Card>
                    <CardHeader className="pb-3">
                        <CardTitle className="text-base">주최자 — 처리할 금액</CardTitle>
                    </CardHeader>
                    <CardContent className="space-y-3">
                        <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-3 lg:grid-cols-5">
                            <Stat label={`${round.currency} 합계`}
                                  value={formatAmount(summary.totalAmount, round.currency)} />
                            <Stat label="기부금 뺀 금액" value={formatKrw(summary.totalItemsKrw)} />
                            <Stat label="이체 합계 (기부금 포함)" value={formatKrw(summary.totalTransferKrw)} />
                            <Stat label={`미입금 ${summary.awaitingDeposit.length}건`}
                                  value={formatKrw(summary.outstandingKrw)} />
                            <Stat label="총 포켓코인" value={formatCoins(summary.totalCoins)} />
                        </dl>
                        <div className="border-t pt-3">
                            <dt className="text-xs text-muted-foreground">차수 전체 환차손익</dt>
                            <dd className="mt-0.5">
                                <FxDelta delta={summary.fxKrw} settled={isRoundSettled(round)} />
                            </dd>
                        </div>
                        {summary.awaitingDeposit.length > 0 && (
                            <div className="border-t pt-3 text-sm">
                                <span className="font-medium">미입금 </span>
                                <span className="text-muted-foreground">
                                    {summary.awaitingDeposit.map((o) => o.applicantName).join(", ")}
                                </span>
                            </div>
                        )}
                    </CardContent>
                </Card>
            )}

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">신청서 {orders.length}건</CardTitle>
                    <div className="flex flex-wrap gap-2 pt-1">
                        {(Object.keys(ORDER_STATUS_LABELS) as (keyof typeof ORDER_STATUS_LABELS)[])
                            .map((s) => (
                                <Badge key={s} variant="outline">
                                    {ORDER_STATUS_LABELS[s]} {summary.statusCounts[s]}
                                </Badge>
                            ))}
                    </div>
                </CardHeader>
                <CardContent className="space-y-2">
                    {orders.length === 0 ? (
                        <div className="space-y-3 py-8 text-center">
                            <p className="text-sm text-muted-foreground">아직 신청서가 없습니다.</p>
                            {acceptsOrders(round) && (
                                <Button asChild variant="outline" size="sm">
                                    <Link href={`/pokemon/${round.hostHandle}/${round.sequence}/new`}>
                                        첫 신청서 작성하기
                                    </Link>
                                </Button>
                            )}
                        </div>
                    ) : (
                        <OrderRows
                            orders={paginate(orders, page).items}
                            rounds={[round]}
                            currentRate={currentRate.toKrw}
                        />
                    )}

                    <Pager
                        page={paginate(orders, page).page}
                        totalPages={paginate(orders, page).totalPages}
                        total={orders.length}
                        onChange={setPage}
                    />
                </CardContent>
            </Card>
        </main>
    )
}
