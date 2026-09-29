"use client"

import type { ReactNode } from "react"
import { Badge } from "@/components/ui/badge"
import {
    ORDER_STATUS_LABELS,
    ROUND_STATUS_LABELS,
    formatSignedKrw,
    type PokemonOrderStatus,
    type PokemonRoundStatus,
} from "@/lib/pokemon"

/**
 * 손익 색. 국내 관례대로 <b>이득이 빨강, 손해가 파랑</b>이다 — 서양 관례(이득 초록)와 반대이니
 * 다른 화면에서 가져다 쓸 때 주의한다. 0 은 색을 입히지 않는다.
 *
 * <p>다크 모드에서 600 계열은 어두운 배경에 묻히므로 한 단계 밝은 쪽을 쓴다.
 */
function toneClass(value: number): string {
    if (value > 0) return "text-red-600 dark:text-red-500"
    if (value < 0) return "text-blue-600 dark:text-blue-400"
    return "text-muted-foreground"
}

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

/**
 * 환차손익. 이 화면에서 가장 크게 읽혀야 하는 숫자라 굵고 크게 낸다.
 * 확정/평가를 함께 드러낸다 — 같은 숫자라도 의미가 다르다.
 */
export function FxDelta({ delta, settled }: { delta: number; settled: boolean }) {
    return (
        <span className="flex flex-wrap items-baseline gap-x-1.5">
            <span className={`text-xl font-bold tracking-tight ${toneClass(delta)}`}>
                {formatSignedKrw(delta)}
            </span>
            <span className="text-xs text-muted-foreground">{settled ? "확정" : "평가"}</span>
        </span>
    )
}

export function BankAccountLine({ account }: { account: string }) {
    return <p className="text-xs text-muted-foreground">입금 계좌 · {account}</p>
}

export function RateLine({
    rate,
    fetchedAt,
    stale,
    note,
}: {
    rate: string
    fetchedAt: string
    stale: boolean
    note?: string
}) {
    return (
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <Badge variant="secondary">{rate}</Badge>
            {note !== undefined && <span>{note}</span>}
            <span>기준 {new Date(fetchedAt).toLocaleString("ko-KR")}</span>
            {stale && <Badge variant="destructive">환율 갱신 실패 — 지난 값</Badge>}
        </div>
    )
}
