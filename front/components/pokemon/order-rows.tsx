"use client"

import Link from "next/link"
import { MessageSquare } from "lucide-react"
import { OrderProgressBar } from "@/components/pokemon/progress-steps"
import { OrderStatusBadge } from "@/components/pokemon/pokemon-bits"
import {
    formatAmount, formatCoins, formatKrw, formatSignedKrw, fxDelta, orderCoins,
    type PokemonOrder, type PokemonRound,
} from "@/lib/pokemon"

/** 한 차수의 신청서 목록. 금액은 그 차수의 통화로 찍는다. */
export default function OrderRows({
    orders,
    round,
    currentRate,
}: {
    orders: PokemonOrder[]
    round: PokemonRound
    /** 그 차수 통화의 현재 환율 — 환차손익을 내는 데 쓴다. */
    currentRate: number
}) {
    return (
        <>
            {orders.map((order) => {
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
                                <div className="font-medium">{order.applicantName}</div>
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
                                <dd>{formatAmount(order.totalAmount, round.currency)}</dd>
                            </div>
                            <div>
                                <dt className="text-xs text-muted-foreground">이체할 금액</dt>
                                <dd className="font-semibold">{formatKrw(order.transferKrw)}</dd>
                            </div>
                            <div>
                                <dt className="text-xs text-muted-foreground">환차손익</dt>
                                <dd className="text-sm">
                                    {formatSignedKrw(fxDelta(order, round, currentRate))}
                                </dd>
                            </div>
                        </dl>
                    </Link>
                )
            })}
        </>
    )
}
