"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { AlertTriangle, Coins, Plus, RefreshCw, Settings } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import RoundList from "@/components/pokemon/round-list"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import type { PokemonHome } from "@/lib/pokemon"

/** 첫 화면 — 최근에 열린 공동구매들. 차수를 열려면 먼저 내 주소를 정해야 한다. */
export default function PokemonHomePage() {
    const [home, setHome] = useState<PokemonHome | null>(null)
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)

    const load = useCallback(async () => {
        try {
            setHome(await pokemonAPI.getHome())
            setLoadState("ready")
            setError(null)
        } catch (caught) {
            setLoadState("failed")
            setError(describeGameApiError(caught, "공동구매 목록을 불러오지 못했습니다."))
        }
    }, [])

    useEffect(() => {
        void load()
    }, [load])

    if (loadState === "loading") {
        return (
            <main className="mx-auto max-w-4xl space-y-4 px-4 py-8">
                <Skeleton className="h-10 w-64" />
                <Skeleton className="h-48 w-full" />
            </main>
        )
    }

    if (loadState === "failed" || home === null) {
        return (
            <main className="mx-auto max-w-4xl px-4 py-8">
                <Alert variant="destructive">
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription className="flex items-center justify-between gap-4">
                        <span>{error}</span>
                        <Button size="sm" variant="outline" onClick={() => void load()}>
                            <RefreshCw className="mr-1 h-3 w-3" /> 다시 시도
                        </Button>
                    </AlertDescription>
                </Alert>
            </main>
        )
    }

    return (
        <main className="mx-auto max-w-4xl space-y-6 px-4 py-8">
            <header className="flex flex-wrap items-start justify-between gap-3">
                <div className="space-y-1">
                    <h1 className="flex items-center gap-2 text-2xl font-bold">
                        <Coins className="h-6 w-6" /> 포켓코인 공동구매
                    </h1>
                    <p className="text-sm text-muted-foreground">
                        주최자가 차수를 열면 누구나 신청할 수 있습니다. 로그인 없이도 신청됩니다.
                    </p>
                </div>

                <div className="flex flex-wrap items-center gap-2">
                    {home.myHandle !== null && (
                        <>
                            <Button asChild variant="outline">
                                <Link href="/pokemon/products">
                                    <Settings className="mr-1 h-4 w-4" /> 내 상품표
                                </Link>
                            </Button>
                            <Button asChild variant="outline">
                                <Link href={`/pokemon/${home.myHandle}`}>내 공동구매</Link>
                            </Button>
                        </>
                    )}
                    {home.loggedIn && (
                        <Button asChild>
                            <Link href="/pokemon/new">
                                <Plus className="mr-1 h-4 w-4" /> 공동구매 열기
                            </Link>
                        </Button>
                    )}
                </div>
            </header>

            {!home.loggedIn && (
                <Alert>
                    <AlertDescription>
                        공동구매를 <b>열려면</b> 로그인이 필요합니다. 신청은 로그인 없이도 됩니다.
                    </AlertDescription>
                </Alert>
            )}

            <Card>
                <CardHeader className="pb-3">
                    <CardTitle className="text-base">열린 공동구매 {home.rounds.length}개</CardTitle>
                </CardHeader>
                <CardContent>
                    <RoundList rounds={home.rounds} showHost emptyMessage="아직 열린 공동구매가 없습니다." />
                </CardContent>
            </Card>
        </main>
    )
}
