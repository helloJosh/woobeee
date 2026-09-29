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
    canModifyOrder,
    formatRate,
    type PokemonBoard,
    type PokemonOrderDetail,
    type PokemonSelection,
} from "@/lib/pokemon"

/**
 * 신청서 수정. 저장하면 금액이 <b>수정 시점 환율</b>로 다시 잡힌다 — 주문 내용이 바뀌면
 * 이체할 금액도 바뀌므로 옛 환율을 붙들고 있을 이유가 없다.
 */
export default function PokemonEditOrderPage() {
    const params = useParams<{ orderId: string }>()
    const router = useRouter()
    const { user, isAuthenticated } = useAuth()

    const orderId = Number(params?.orderId)
    const [detail, setDetail] = useState<PokemonOrderDetail | null>(null)
    const [board, setBoard] = useState<PokemonBoard | null>(null)
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)

    const load = useCallback(async () => {
        if (!Number.isInteger(orderId)) {
            setLoadState("failed")
            setError("신청서를 찾을 수 없습니다.")
            return
        }
        try {
            // 상품표는 board 에만 있다 — 세부 응답은 신청서 한 건만 담는다.
            const [loadedDetail, loadedBoard] = await Promise.all([
                pokemonAPI.getOrder(orderId),
                pokemonAPI.getBoard(),
            ])
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
        const sellable = new Set(products.map((product) => product.id))
        const selection: PokemonSelection = {}
        for (const item of detail.order.items) {
            if (item.productId !== null && sellable.has(item.productId)) {
                selection[item.productId] = item.quantity
            }
        }
        return {
            selection,
            applicantName: detail.order.guest ? detail.order.applicantName : "",
            depositorName: detail.order.depositorName ?? "",
            donation: detail.order.donationKrw > 0 ? String(detail.order.donationKrw) : "",
            extraInr: detail.order.extraInr > 0 ? String(detail.order.extraInr) : "",
            memo: detail.order.memo ?? "",
        }
    }, [detail, products])

    const droppedItems = useMemo(() => {
        if (detail === null) return []
        const sellable = new Set(products.map((product) => product.id))
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
                    <Link href="/pokemon">
                        <ArrowLeft className="mr-1 h-4 w-4" /> 목록으로
                    </Link>
                </Button>
            </main>
        )
    }

    const { order, canManage } = detail

    // 서버도 같은 규칙으로 막지만, 고칠 수 없는 신청서의 폼을 그려 놓고 저장에서 튕기는 것보다
    // 들어오는 순간 알려주는 편이 낫다.
    if (!canModifyOrder(order, canManage)) {
        return (
            <main className="mx-auto max-w-3xl space-y-3 px-4 py-8">
                <Alert>
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription>
                        준비가 시작된 뒤에는 수정할 수 없습니다. 운영자에게 문의해 주세요.
                    </AlertDescription>
                </Alert>
                <Button asChild variant="ghost" size="sm">
                    <Link href={`/pokemon/${orderId}`}>
                        <ArrowLeft className="mr-1 h-4 w-4" /> 신청서로
                    </Link>
                </Button>
            </main>
        )
    }

    return (
        <main className="mx-auto max-w-3xl space-y-6 px-4 py-8">
            <header className="space-y-2">
                <Button asChild variant="ghost" size="sm" className="-ml-2">
                    <Link href={`/pokemon/${orderId}`}>
                        <ArrowLeft className="mr-1 h-4 w-4" /> 신청서
                    </Link>
                </Button>
                <h1 className="text-2xl font-bold">신청서 수정</h1>
                <RateLine
                    rate={formatRate(board.rate.inrToKrw)}
                    fetchedAt={board.rate.fetchedAt}
                    stale={board.rate.stale}
                />
                <p className="text-xs text-muted-foreground">
                    저장하면 이체할 금액이 <b>지금 환율</b>로 다시 계산됩니다.
                </p>
            </header>

            <OrderForm
                products={products}
                rate={board.rate.inrToKrw}
                bankAccount={board.bankAccount}
                initial={initial}
                loggedIn={isAuthenticated}
                memberName={order.guest ? null : (user?.name ?? order.applicantName)}
                nameLocked={!order.guest}
                submitLabel="수정 저장"
                droppedItems={droppedItems}
                onSubmit={async (body) => {
                    await pokemonAPI.updateOrder(orderId, body)
                    router.replace(`/pokemon/${orderId}`)
                }}
            />
        </main>
    )
}
