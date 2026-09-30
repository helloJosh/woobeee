"use client"

import { useMemo, useState } from "react"
import Link from "next/link"
import { Plus, Settings } from "lucide-react"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import OrderRows from "@/components/pokemon/order-rows"
import Pager from "@/components/pokemon/pager"
import { RoundProgressSteps } from "@/components/pokemon/progress-steps"
import { BankAccountLine, FxDelta, RateLine, Stat } from "@/components/pokemon/pokemon-bits"
import {
    ORDER_STATUS_LABELS, RATE_MODE_LABELS, ROUND_STATUS_FLOW, ROUND_STATUS_LABELS,
    acceptsOrders, formatAmount, formatCoins, formatKrw, formatRate, isRoundSettled,
    paginate, rateFor, summarizeRound,
    type PokemonRoundBoard, type PokemonRoundStatus,
} from "@/lib/pokemon"

const ALL_ROUND_STATUSES: PokemonRoundStatus[] = [...ROUND_STATUS_FLOW, "CANCELLED"]

/**
 * 차수 하나의 본문 — 진행도·주최자 집계·신청서 목록. 차수 페이지와 주최자 페이지의 탭이
 * 함께 쓴다. 한 벌만 두어야 두 곳이 어긋나지 않는다.
 */
export default function RoundBoard({
    board,
    onChangeStatus,
}: {
    board: PokemonRoundBoard
    onChangeStatus: (next: PokemonRoundStatus) => void
}) {
    const [page, setPage] = useState(1)
    const { round, currentRate, orders } = board
    const summary = useMemo(
        () => summarizeRound(orders, round, currentRate.toKrw),
        [orders, round, currentRate],
    )
    const applyRate = rateFor(round, currentRate.toKrw)
    const paged = paginate(orders, page)

    return (
        <div className="space-y-6">
            <Card>
                <CardHeader className="pb-3">
                    <div className="flex flex-wrap items-center justify-between gap-2">
                        <CardTitle className="text-base">진행도</CardTitle>
                        <div className="flex items-center gap-2">
                            {round.canManage ? (
                                <Select
                                    value={round.status}
                                    onValueChange={(v) => onChangeStatus(v as PokemonRoundStatus)}
                                >
                                    <SelectTrigger className="h-8 w-28 text-xs"><SelectValue /></SelectTrigger>
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
                                        <Settings className="mr-1 h-3 w-3" /> 관리
                                    </Link>
                                </Button>
                            )}
                            {acceptsOrders(round) && (
                                <Button asChild size="sm">
                                    <Link href={`/pokemon/${round.hostHandle}/${round.sequence}/new`}>
                                        <Plus className="mr-1 h-3 w-3" /> 신청
                                    </Link>
                                </Button>
                            )}
                        </div>
                    </div>
                </CardHeader>
                <CardContent className="space-y-3">
                    <RoundProgressSteps status={round.status} />
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
                </CardContent>
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
                        <>
                            <OrderRows orders={paged.items} rounds={[round]} currentRate={currentRate.toKrw} />
                            <Pager page={paged.page} totalPages={paged.totalPages}
                                   total={orders.length} onChange={setPage} />
                        </>
                    )}
                </CardContent>
            </Card>
        </div>
    )
}
