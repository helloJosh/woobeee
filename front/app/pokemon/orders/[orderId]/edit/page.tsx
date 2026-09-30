"use client"

import { useCallback, useEffect, useMemo, useState } from "react"
import Link from "next/link"
import { useParams, useRouter } from "next/navigation"
import { AlertTriangle, ArrowLeft, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import OrderForm, { type OrderFormValues } from "@/components/pokemon/order-form"
import { RateLine } from "@/components/pokemon/pokemon-bits"
import { useAuth } from "@/hooks/use-auth"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import {
    RATE_MODE_LABELS, canModifyOrder, formatRate, rateFor, roundTitle,
    type PokemonOrderDetail, type PokemonRoundBoard, type PokemonSelection,
} from "@/lib/pokemon"

/** 신청서 수정. 금액은 차수 규칙대로 다시 계산된다. */
export default function PokemonEditOrderPage() {
    const params = useParams<{ orderId: string }>()
    const router = useRouter()
    const { user } = useAuth()
    const orderId = Number(params?.orderId)

    const [detail, setDetail] = useState<PokemonOrderDetail | null>(null)
    const [board, setBoard] = useState<PokemonRoundBoard | null>(null)
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)

    const load = useCallback(async () => {
        if (!Number.isInteger(orderId)) {
            setLoadState("failed")
            setError("신청서를 찾을 수 없습니다.")
            return
        }
        try {
            // 상품표는 차수 화면에만 있다 — 세부 응답은 신청서 한 건만 담는다.
            const loadedDetail = await pokemonAPI.getOrder(orderId)
            const loadedBoard = await pokemonAPI.getRoundBoard(
                loadedDetail.round.hostHandle, loadedDetail.round.sequence)
            setDetail(loadedDetail)
            setBoard(loadedBoard)
            setLoadState("ready")
            setError(null)
        } catch (caught) {
            setLoadState("failed")
            setError(describeGameApiError(caught, "신청서를 불러오지 못했습니다."))
        }
    }, [orderId])

    useEffect(() => {
        void load()
    }, [load])

    const products = board?.products ?? []

    /** 지금도 고를 수 있는 항목만 폼에 싣는다. 내려간 상품은 아래에서 따로 알린다. */
    const initial = useMemo<OrderFormValues | null>(() => {
        if (detail === null) return null
        const sellable = new Set(products.map((p) => p.id))
        const selection: PokemonSelection = {}
        for (const item of detail.order.items) {
            if (item.productId !== null && sellable.has(item.productId)) {
                selection[item.productId] = item.quantity
            }
        }
        return {
            selection,
            depositorName: detail.order.depositorName ?? "",
            donation: detail.order.donationKrw > 0 ? String(detail.order.donationKrw) : "",
            extraAmount: detail.order.extraAmount > 0 ? String(detail.order.extraAmount) : "",
            memo: detail.order.memo ?? "",
        }
    }, [detail, products])

    const droppedItems = useMemo(() => {
        if (detail === null) return []
        const sellable = new Set(products.map((p) => p.id))
        return detail.order.items
            .filter((item) => item.productId === null || !sellable.has(item.productId))
            .map((item) => item.productName)
    }, [detail, products])

    if (loadState === "loading") {
        return (
            <main className="mx-auto max-w-3xl space-y-4 px-4 py-8">
                <Skeleton className="h-10 w-48" />
                <Skeleton className="h-64 w-full" />
            </main>
        )
    }

    if (loadState === "failed" || detail === null || board === null || initial === null) {
        return (
            <main className="mx-auto max-w-3xl space-y-3 px-4 py-8">
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

    const { order, round, rate, canManage } = detail
    const backToOrder = `/pokemon/orders/${orderId}`

    const header = (
        <header className="space-y-2">
            <Button asChild variant="ghost" size="sm" className="-ml-2">
                <Link href={backToOrder}><ArrowLeft className="mr-1 h-4 w-4" /> 신청서</Link>
            </Button>
            <h1 className="text-2xl font-bold">신청서 수정</h1>
            <p className="text-xs text-muted-foreground">{roundTitle(round)}</p>
            <RateLine
                rate={formatRate(rateFor(round, rate.toKrw), round.currency)}
                fetchedAt={round.quotedAt}
                stale={rate.stale}
                note={RATE_MODE_LABELS[round.rateMode]}
            />
        </header>
    )

    // 서버도 같은 규칙으로 막지만, 고칠 수 없는 신청서의 폼을 그려 놓고 저장에서 튕기는 것보다
    // 들어오는 순간 알려주는 편이 낫다.
    if (!canModifyOrder(order, canManage)) {
        return (
            <main className="mx-auto max-w-3xl space-y-6 px-4 py-8">
                {header}
                <Alert>
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription>
                        입금이 확인된 뒤에는 수정할 수 없습니다. 주최자에게 문의해 주세요.
                    </AlertDescription>
                </Alert>
            </main>
        )
    }

    return (
        <main className="mx-auto max-w-3xl space-y-6 px-4 py-8">
            {header}
            <OrderForm
                round={round}
                products={products}
                currentRate={rate.toKrw}
                initial={initial}
                memberName={user?.name ?? order.applicantName}
                submitLabel="수정 저장"
                droppedItems={droppedItems}
                onSubmit={async (body) => {
                    await pokemonAPI.updateOrder(orderId, body)
                    router.replace(backToOrder)
                }}
            />
        </main>
    )
}
