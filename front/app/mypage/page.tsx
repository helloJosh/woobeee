"use client"

import { useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import { Loader2 } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import ProfileImageEditor from "@/components/auth/profile-image-editor"
import Pager from "@/components/pokemon/pager"
import Link from "next/link"
import { authAPI, pokemonAPI } from "@/lib/api"
import { buildAuthHref } from "@/lib/auth-redirect"
import { useAuth } from "@/hooks/use-auth"
import {
    ORDER_STATUS_LABELS,
    ROUND_STATUS_LABELS,
    formatKrw,
    myOrderHref,
    myOrderRoundHref,
    myOrderRoundTitle,
    paginate,
    summarizeMyOrders,
    type PokemonMyOrder,
} from "@/lib/pokemon"
import type { MemberProfile } from "@/lib/types"

const MY_PAGE_PATH = "/mypage"

/**
 * 상단 탭의 마이페이지. 프로필(이미지·닉네임)을 보여 주고 바꾼다.
 * 회원 전용이라 로그인하지 않았으면 여기로 돌아오는 `next` 를 달아 로그인 화면으로 보낸다.
 */
export default function MyPage() {
    const router = useRouter()
    const { loading, isAuthenticated } = useAuth()

    const [profile, setProfile] = useState<MemberProfile | null>(null)
    const [loadFailed, setLoadFailed] = useState(false)
    const [error, setError] = useState<string | null>(null)
    // 프로필과 따로 도착한다. 세 상태를 구분해야 한다 — 도착 전에 "없습니다" 도, 실패했는데
    // "없습니다" 도 거짓말이다(빈 목록과 못 불러온 것은 다르다).
    const [orders, setOrders] = useState<PokemonMyOrder[]>([])
    const [ordersState, setOrdersState] = useState<"loading" | "ready" | "failed">("loading")
    const [ordersPage, setOrdersPage] = useState(1)

    useEffect(() => {
        if (!loading && !isAuthenticated) {
            router.replace(buildAuthHref("/login", MY_PAGE_PATH))
        }
    }, [loading, isAuthenticated, router])

    useEffect(() => {
        if (loading || !isAuthenticated) {
            return
        }

        let active = true

        authAPI
            .me()
            .then((loadedProfile) => {
                if (active) {
                    setProfile(loadedProfile)
                }
            })
            .catch(() => {
                if (!active) {
                    return
                }
                // 스피너를 걷는다. 실패한 채로 계속 돌리면 "곧 됩니다" 라는 거짓말이 된다.
                setLoadFailed(true)
                setError("정보를 불러오지 못했습니다.")
            })

        // 프로필과 따로 받는다. 한쪽이 실패해도 다른 쪽은 그려져야 한다.
        pokemonAPI
            .getMyOrders()
            .then((loadedOrders) => {
                if (active) {
                    setOrders(loadedOrders)
                    setOrdersState("ready")
                }
            })
            .catch(() => {
                if (active) {
                    setOrdersState("failed")
                }
            })

        return () => {
            active = false
        }
    }, [loading, isAuthenticated])

    // 서버가 전부 내려주고 여기서 자른다 — 현황 집계(진행 중·미입금)는 어차피 전부 필요하다.
    const pagedOrders = paginate(orders, ordersPage)

    if (loading || (!profile && !loadFailed)) {
        return (
            <div className="flex min-h-[50vh] items-center justify-center">
                <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                <span className="sr-only">불러오는 중입니다</span>
            </div>
        )
    }

    return (
        <main className="mx-auto w-full max-w-3xl space-y-10 px-4 py-10">
            <section className="space-y-4">
                <h1 className="text-2xl font-semibold">마이페이지</h1>

                {error ? (
                    <Alert variant="destructive">
                        <AlertDescription className="text-sm">{error}</AlertDescription>
                    </Alert>
                ) : null}

                {profile ? (
                    <ProfileImageEditor profile={profile} onProfileChange={setProfile} />
                ) : null}
            </section>

            <section className="space-y-4">
                <h2 className="text-lg font-medium">내가 참가한 공동구매</h2>

                {orders.length === 0 ? (
                    <p className="text-sm text-muted-foreground">
                        {ordersState === "loading"
                            ? "불러오는 중입니다…"
                            : ordersState === "failed"
                              ? "참가한 공동구매를 불러오지 못했습니다."
                              : "아직 참가한 공동구매가 없습니다."}
                    </p>
                ) : (
                    <>
                        <MyOrdersSummary orders={orders} />
                        <ul className="space-y-2">
                            {pagedOrders.items.map((order) => (
                                <li
                                    key={order.orderId}
                                    className="flex items-center justify-between gap-3 rounded-md border px-4 py-3 text-sm"
                                >
                                    <div className="min-w-0">
                                        <Link href={myOrderRoundHref(order)} className="font-medium hover:underline">
                                            {myOrderRoundTitle(order)}
                                        </Link>
                                        <p className="truncate text-xs text-muted-foreground">
                                            {order.hostHandle} · 차수 {ROUND_STATUS_LABELS[order.roundStatus]} · 예상{" "}
                                            {formatKrw(order.estimatedKrw)}
                                        </p>
                                    </div>
                                    <Link
                                        href={myOrderHref(order)}
                                        className="shrink-0 rounded-md border px-2.5 py-1 text-xs hover:bg-accent"
                                    >
                                        {ORDER_STATUS_LABELS[order.orderStatus]}
                                    </Link>
                                </li>
                            ))}
                        </ul>
                        <Pager
                            page={pagedOrders.page}
                            totalPages={pagedOrders.totalPages}
                            total={orders.length}
                            onChange={setOrdersPage}
                        />
                    </>
                )}
            </section>
        </main>
    )
}

/** 현황 한 줄 — 판단은 lib/pokemon.ts 의 summarizeMyOrders 가 한다. */
function MyOrdersSummary({ orders }: { orders: PokemonMyOrder[] }) {
    const summary = summarizeMyOrders(orders)
    return (
        <p className="text-sm text-muted-foreground">
            진행 중 {summary.inProgress} · 전달완료 {summary.delivered}
            {summary.cancelled > 0 ? ` · 취소 ${summary.cancelled}` : ""}
            {summary.unpaidKrw > 0 ? ` · 입금할 금액 ${formatKrw(summary.unpaidKrw)}` : ""}
        </p>
    )
}
