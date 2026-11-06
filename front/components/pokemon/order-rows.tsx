"use client"

import Link from "next/link"
import { MessageSquare } from "lucide-react"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { OrderProgressBar } from "@/components/pokemon/progress-steps"
import { OrderStatusBadge } from "@/components/pokemon/pokemon-bits"
import {
    ORDER_STATUS_LABELS, ORDER_STATUS_OPTIONS,
    formatAmount, formatCoins, formatKrw, orderCoins,
    type PokemonOrder, type PokemonOrderStatus, type PokemonRound,
} from "@/lib/pokemon"

/** 한 차수의 신청서 목록. 금액은 그 차수의 통화로 찍는다. */
export default function OrderRows({
    orders,
    round,
    note,
    onChangeStatus,
}: {
    orders: PokemonOrder[]
    round: PokemonRound
    /** "○월 ○일 환율 기준 예상" — 목록의 원화가 모두 예상 금액임을 밝힌다(POKEMON-AC-39). */
    note: string
    /** 넘기면(=운영자) 행마다 상태를 바로 바꾸는 선택창을 그린다. 없으면 배지만 보인다. */
    onChangeStatus?: (order: PokemonOrder, next: PokemonOrderStatus) => void
}) {
    return (
        <>
            <p className="text-xs text-muted-foreground">{note}</p>
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
                            {onChangeStatus ? (
                                // 행 전체가 링크라서 선택창의 클릭이 상세로 새지 않게 막는다.
                                // 선택 항목은 포털에 그려지지만 React 이벤트는 이 요소까지 올라온다.
                                <span
                                    onClick={(event) => {
                                        event.preventDefault()
                                        event.stopPropagation()
                                    }}
                                >
                                    <Select
                                        value={order.status}
                                        onValueChange={(next) => onChangeStatus(order, next as PokemonOrderStatus)}
                                    >
                                        <SelectTrigger
                                            className="h-8 w-28 text-xs"
                                            aria-label={`${order.applicantName} 신청서 상태`}
                                        >
                                            <SelectValue />
                                        </SelectTrigger>
                                        <SelectContent>
                                            {ORDER_STATUS_OPTIONS.map((status) => (
                                                <SelectItem key={status} value={status}>
                                                    {ORDER_STATUS_LABELS[status]}
                                                </SelectItem>
                                            ))}
                                        </SelectContent>
                                    </Select>
                                </span>
                            ) : (
                                <OrderStatusBadge status={order.status} />
                            )}
                        </div>

                        <div className="mt-3">
                            <OrderProgressBar status={order.status} />
                        </div>

                        <dl className="mt-3 grid grid-cols-2 gap-2 text-sm sm:grid-cols-3">
                            <div>
                                <dt className="text-xs text-muted-foreground">받는 포켓코인</dt>
                                <dd className="font-semibold">{formatCoins(orderCoins(order))}</dd>
                            </div>
                            <div>
                                <dt className="text-xs text-muted-foreground">상품 합계</dt>
                                <dd>{formatAmount(order.totalAmount, round.currency)}</dd>
                            </div>
                            <div>
                                <dt className="text-xs text-muted-foreground">예상 금액</dt>
                                <dd className="font-semibold">{formatKrw(order.estimatedKrw)}</dd>
                            </div>
                        </dl>
                    </Link>
                )
            })}
        </>
    )
}
