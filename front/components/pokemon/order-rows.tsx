"use client"

import Link from "next/link"
import { MessageSquare } from "lucide-react"
import { OrderProgressBar } from "@/components/pokemon/progress-steps"
import { OrderStatusBadge } from "@/components/pokemon/pokemon-bits"
import {
    formatAmount, formatCoins, formatKrw, formatSignedKrw, fxDelta, orderCoins, roundOf, roundTitle,
    type PokemonOrder, type PokemonRound,
} from "@/lib/pokemon"

/**
 * 신청서 목록 한 벌 — 차수 화면과 주최자 화면(여러 차수를 모아 볼 때)이 함께 쓴다.
 *
 * <p>금액은 그 신청서가 속한 차수의 통화로 찍어야 한다. 여러 차수를 모아 보면 INR 과 JPY 가
 * 한 화면에 섞이므로, 차수를 못 찾으면 통화를 지어내지 않고 원화만 보여준다.
 */
export default function OrderRows({
    orders,
    rounds,
    currentRate,
    showRound = false,
}: {
    orders: PokemonOrder[]
    rounds: PokemonRound[]
    /** 통화별 현재 환율. 없으면 환차손익을 내지 않는다. */
    currentRate?: number
    showRound?: boolean
}) {
    return (
        <>
            {orders.map((order) => {
                const round = roundOf(order, rounds)

                return (
                    <Link
                        key={order.id}
                        href={`/pokemon/orders/${order.id}`}
                        className={`block rounded-md border p-3 transition-colors hover:bg-accent ${
                            order.status === "CANCELLED" ? "opacity-50" : ""
                        }`}
                    >
                        <div className="flex flex-wrap items-start justify-between gap-2">
                            <div>
                                <div className="font-medium">
                                    {showRound && round !== null && (
                                        <span className="text-muted-foreground">
                                            {roundTitle(round)} ·{" "}
                                        </span>
                                    )}
                                    {order.applicantName}
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
                            <OrderStatusBadge status={order.status} />
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
                                <dd>
                                    {round === null
                                        ? order.totalAmount
                                        : formatAmount(order.totalAmount, round.currency)}
                                </dd>
                            </div>
                            <div>
                                <dt className="text-xs text-muted-foreground">이체할 금액</dt>
                                <dd className="font-semibold">{formatKrw(order.transferKrw)}</dd>
                            </div>
                            <div>
                                <dt className="text-xs text-muted-foreground">환차손익</dt>
                                <dd className="text-sm">
                                    {round === null || currentRate === undefined
                                        ? "—"
                                        : formatSignedKrw(fxDelta(order, round, currentRate))}
                                </dd>
                            </div>
                        </dl>
                    </Link>
                )
            })}
        </>
    )
}
