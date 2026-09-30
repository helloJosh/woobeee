"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { useParams, usePathname, useRouter } from "next/navigation"
import { AlertTriangle, ArrowLeft, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import OrderForm, { EMPTY_ORDER_FORM } from "@/components/pokemon/order-form"
import { RateLine } from "@/components/pokemon/pokemon-bits"
import { useAuth } from "@/hooks/use-auth"
import { buildAuthHref } from "@/lib/auth-redirect"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import {
    RATE_MODE_LABELS, acceptsOrders, formatRate, rateFor, roundTitle,
    type PokemonRoundBoard,
} from "@/lib/pokemon"

/** 차수에 신청하기. 저장하면 그 신청서의 세부 페이지로 보낸다. */
export default function PokemonNewOrderPage() {
    const params = useParams<{ handle: string; sequence: string }>()
    const router = useRouter()
    const { user, isAuthenticated, loading: authLoading } = useAuth()
    const pathname = usePathname()
    const handle = params?.handle ?? ""
    const sequence = Number(params?.sequence)

    const [board, setBoard] = useState<PokemonRoundBoard | null>(null)
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)

    const load = useCallback(async () => {
        if (!Number.isInteger(sequence)) {
            setLoadState("failed")
            setError("공동구매를 찾을 수 없습니다.")
            return
        }
        try {
            setBoard(await pokemonAPI.getRoundBoard(handle, sequence))
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

    // 신청은 회원만 할 수 있다. 폼을 그려 놓고 저장에서 튕기는 것보다 바로 로그인으로 보낸다.
    useEffect(() => {
        if (!authLoading && !isAuthenticated) {
            router.replace(buildAuthHref("/login", pathname))
        }
    }, [authLoading, isAuthenticated, pathname, router])

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

    const { round, products, currentRate } = board
    const backToRound = `/pokemon/${round.hostHandle}/${round.sequence}`

    const header = (
        <header className="space-y-2">
            <Button asChild variant="ghost" size="sm" className="-ml-2">
                <Link href={backToRound}>
                    <ArrowLeft className="mr-1 h-4 w-4" /> {roundTitle(round)}
                </Link>
            </Button>
            <h1 className="text-2xl font-bold">신청서 작성</h1>
            <RateLine
                rate={formatRate(rateFor(round, currentRate.toKrw), round.currency)}
                fetchedAt={round.quotedAt}
                stale={currentRate.stale}
                note={RATE_MODE_LABELS[round.rateMode]}
            />
        </header>
    )

    // 모집중이 아니면 폼을 그려 놓고 저장에서 튕기는 것보다 들어오는 순간 알려주는 편이 낫다.
    if (!acceptsOrders(round)) {
        return (
            <main className="mx-auto max-w-3xl space-y-6 px-4 py-8">
                {header}
                <Alert>
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription>
                        마감된 공동구매라 더 이상 신청할 수 없습니다.
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
                currentRate={currentRate.toKrw}
                initial={EMPTY_ORDER_FORM}
                memberName={user?.name ?? null}
                submitLabel="신청서 저장"
                onSubmit={async (body) => {
                    const created = await pokemonAPI.createOrder(round.id, body)
                    // 저장한 신청서를 바로 볼 수 있게 보낸다. replace 라 뒤로가기가 빈 폼으로
                    // 돌아가지 않는다.
                    router.replace(`/pokemon/orders/${created.id}`)
                }}
            />
        </main>
    )
}
