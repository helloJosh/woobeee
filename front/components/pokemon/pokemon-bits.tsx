"use client"

import type { ReactNode } from "react"
import { Badge } from "@/components/ui/badge"
import {
    ORDER_STATUS_LABELS,
    ROUND_STATUS_LABELS,
    type PokemonOrderStatus,
    type PokemonRoundStatus,
} from "@/lib/pokemon"

export function Stat({ label, value }: { label: string; value: string }) {
    return (
        <div>
            <dt className="text-xs text-muted-foreground">{label}</dt>
            <dd className="font-semibold">{value}</dd>
        </div>
    )
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
    return (
        <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">{label}</span>
            {children}
        </label>
    )
}

export function RoundStatusBadge({ status }: { status: PokemonRoundStatus }) {
    const variant = status === "CANCELLED" ? "outline" : status === "OPEN" ? "default" : "secondary"
    return <Badge variant={variant}>{ROUND_STATUS_LABELS[status]}</Badge>
}

export function OrderStatusBadge({ status }: { status: PokemonOrderStatus }) {
    return (
        <Badge variant={status === "CANCELLED" ? "outline" : "secondary"}>
            {ORDER_STATUS_LABELS[status]}
        </Badge>
    )
}

export function BankAccountLine({ account }: { account: string }) {
    return <p className="text-xs text-muted-foreground">입금 계좌 · {account}</p>
}

/** 오늘 환율과 "○월 ○일 환율 기준 예상" 문구(`rateNote`). 지난 값이면 갱신 실패 배지를 더한다. */
export function RateLine({
    rate,
    stale,
    note,
}: {
    rate: string
    stale: boolean
    note: string
}) {
    return (
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <Badge variant="secondary">{rate}</Badge>
            <span>{note}</span>
            {stale && <Badge variant="destructive">환율 갱신 실패 — 지난 값</Badge>}
        </div>
    )
}
