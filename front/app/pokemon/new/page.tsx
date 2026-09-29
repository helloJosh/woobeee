"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { useRouter } from "next/navigation"
import { AlertTriangle, ArrowLeft, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import OrderForm, { EMPTY_ORDER_FORM } from "@/components/pokemon/order-form"
import { RateLine } from "@/components/pokemon/pokemon-bits"
import { useAuth } from "@/hooks/use-auth"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import { formatRate, type PokemonBoard } from "@/lib/pokemon"

/** 신청서 작성. 저장에 성공하면 그 신청서의 세부 페이지로 보낸다. */
export default function PokemonNewOrderPage() {
    const router = useRouter()
    const { user, isAuthenticated } = useAuth()

    const [board, setBoard] = useState<PokemonBoard | null>(null)
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [loadError, setLoadError] = useState<string | null>(null)

    const load = useCallback(async () => {
        try {
            setBoard(await pokemonAPI.getBoard())
            setLoadState("ready")
            setLoadError(null)
        } catch (error) {
            setLoadState("failed")
            setLoadError(describeGameApiError(error, "상품 정보를 불러오지 못했습니다."))
        }
    }, [])

    useEffect(() => {
        void load()
    }, [load])

    if (loadState === "loading") {
        return (
            <main className="mx-auto max-w-3xl space-y-4 px-4 py-8">
                <Skeleton className="h-10 w-48" />
                <Skeleton className="h-64 w-full" />
            </main>
        )
    }

    if (loadState === "failed" || board === null) {
        return (
            <main className="mx-auto max-w-3xl px-4 py-8">
                <Alert variant="destructive">
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription className="flex items-center justify-between gap-4">
                        <span>{loadError}</span>
                        <Button size="sm" variant="outline" onClick={() => void load()}>
                            <RefreshCw className="mr-1 h-3 w-3" /> 다시 시도
                        </Button>
                    </AlertDescription>
                </Alert>
            </main>
        )
    }

    return (
        <main className="mx-auto max-w-3xl space-y-6 px-4 py-8">
            <header className="space-y-2">
                <Button asChild variant="ghost" size="sm" className="-ml-2">
                    <Link href="/pokemon">
                        <ArrowLeft className="mr-1 h-4 w-4" /> 목록
                    </Link>
                </Button>
                <h1 className="text-2xl font-bold">신청서 작성</h1>
                <RateLine
                    rate={formatRate(board.rate.inrToKrw)}
                    fetchedAt={board.rate.fetchedAt}
                    stale={board.rate.stale}
                />
            </header>

            <OrderForm
                products={board.products}
                rate={board.rate.inrToKrw}
                bankAccount={board.bankAccount}
                initial={EMPTY_ORDER_FORM}
                loggedIn={isAuthenticated}
                memberName={user?.name ?? null}
                nameLocked={isAuthenticated}
                submitLabel="신청서 저장"
                onSubmit={async (body) => {
                    const created = await pokemonAPI.createOrder(body)
                    // 저장한 신청서를 바로 볼 수 있게 세부 페이지로 보낸다. replace 라 뒤로가기가
                    // 빈 폼으로 돌아가지 않는다.
                    router.replace(`/pokemon/${created.id}`)
                }}
            />
        </main>
    )
}
