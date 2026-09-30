"use client"

import { useCallback, useEffect, useState } from "react"
import Link from "next/link"
import { useParams, useRouter, useSearchParams } from "next/navigation"
import { AlertTriangle, ArrowLeft, Plus, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import RoundBoard from "@/components/pokemon/round-board"
import RoundTabs from "@/components/pokemon/round-tabs"
import { pokemonAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import type { PokemonHost, PokemonRoundBoard, PokemonRoundStatus } from "@/lib/pokemon"

/**
 * {@code /pokemon/{handle}} — 한 주최자의 방.
 *
 * <p>차수가 탭으로 나뉘고, 고르면 그 차수 화면이 <b>그 자리에</b> 뜬다 — 옮겨 다니지 않아도
 * 된다. 아무것도 고르지 않으면 가장 최근 차수를 연다. 고른 탭은 주소({@code ?round=2})에
 * 남아 링크를 그대로 공유할 수 있다.
 */
export default function PokemonHostPage() {
    const params = useParams<{ handle: string }>()
    const searchParams = useSearchParams()
    const router = useRouter()
    const handle = params?.handle ?? ""
    const requested = searchParams?.get("round")

    const [host, setHost] = useState<PokemonHost | null>(null)
    const [loadState, setLoadState] = useState<"loading" | "ready" | "failed">("loading")
    const [error, setError] = useState<string | null>(null)

    // 고른 차수의 본문. 탭을 옮길 때마다 받아 오고, 받아 둔 것은 다시 부르지 않는다.
    const [boards, setBoards] = useState<Record<number, PokemonRoundBoard>>({})
    const [boardLoading, setBoardLoading] = useState(false)

    const load = useCallback(async () => {
        try {
            setHost(await pokemonAPI.getHost(handle))
            setLoadState("ready")
            setError(null)
        } catch (caught) {
            setLoadState("failed")
            setError(describeGameApiError(caught, "주최자를 찾지 못했습니다."))
        }
    }, [handle])

    useEffect(() => {
        void load()
    }, [load])

    // 아무것도 고르지 않았으면 가장 최근 차수를 연다(목록이 최신순이라 맨 앞).
    const selected = requested ?? (host?.rounds[0]?.sequence?.toString() ?? "")
    const sequence = selected === "" ? null : Number(selected)

    useEffect(() => {
        if (sequence === null || !Number.isInteger(sequence) || boards[sequence] !== undefined) {
            return
        }
        let cancelled = false
        setBoardLoading(true)
        pokemonAPI.getRoundBoard(handle, sequence)
            .then((board) => {
                if (!cancelled) setBoards((current) => ({ ...current, [sequence]: board }))
            })
            .catch((caught) => {
                if (!cancelled) setError(describeGameApiError(caught, "차수를 불러오지 못했습니다."))
            })
            .finally(() => {
                if (!cancelled) setBoardLoading(false)
            })
        return () => {
            cancelled = true
        }
    }, [handle, sequence, boards])

    const select = (next: string) => {
        router.replace(`/pokemon/${handle}?round=${next}`, { scroll: false })
    }

    if (loadState === "loading") {
        return (
            <main className="mx-auto max-w-4xl space-y-4 px-4 py-8">
                <Skeleton className="h-10 w-48" />
                <Skeleton className="h-48 w-full" />
            </main>
        )
    }

    if (loadState === "failed" || host === null) {
        return (
            <main className="mx-auto max-w-4xl space-y-3 px-4 py-8">
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

    const board = sequence === null ? null : boards[sequence]

    return (
        <main className="mx-auto max-w-4xl space-y-6 px-4 py-8">
            <header className="space-y-2">
                <Button asChild variant="ghost" size="sm" className="-ml-2">
                    <Link href="/pokemon"><ArrowLeft className="mr-1 h-4 w-4" /> 전체 목록</Link>
                </Button>
                <div className="flex flex-wrap items-center justify-between gap-2">
                    <div>
                        <h1 className="text-2xl font-bold">{host.name}의 공동구매</h1>
                        <p className="text-xs text-muted-foreground">/pokemon/{host.handle}</p>
                    </div>
                    {host.isMe && (
                        <Button asChild>
                            <Link href="/pokemon/new">
                                <Plus className="mr-1 h-4 w-4" /> 새 차수 열기
                            </Link>
                        </Button>
                    )}
                </div>
            </header>

            <RoundTabs rounds={host.rounds} selected={selected} onSelect={select} />

            {error !== null && (
                <Alert variant="destructive">
                    <AlertTriangle className="h-4 w-4" />
                    <AlertDescription>{error}</AlertDescription>
                </Alert>
            )}

            {sequence !== null ? (
                boardLoading || board === undefined || board === null ? (
                    <Skeleton className="h-64 w-full" />
                ) : (
                    <RoundBoard
                        board={board}
                        onChangeStatus={(next: PokemonRoundStatus) =>
                            void pokemonAPI.changeRoundStatus(board.round.id, next)
                                .then(async () => {
                                    // 바뀐 차수만 다시 받고, 모아 보기의 건수도 함께 맞춘다.
                                    setBoards((c) => {
                                        const { [sequence]: _dropped, ...rest } = c
                                        return rest
                                    })
                                    await load()
                                })
                                .catch((c) => setError(describeGameApiError(c, "상태를 바꾸지 못했습니다.")))
                        }
                    />
                )
            ) : (
                <Card>
                    <CardContent className="space-y-3 py-10 text-center">
                        <p className="text-sm text-muted-foreground">아직 연 차수가 없습니다.</p>
                        {host.isMe && (
                            <Button asChild size="sm">
                                <Link href="/pokemon/new">첫 차수 열기</Link>
                            </Button>
                        )}
                    </CardContent>
                </Card>
            )}
        </main>
    )
}
