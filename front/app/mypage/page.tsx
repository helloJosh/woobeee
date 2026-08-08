"use client"

import { useCallback, useEffect, useState } from "react"
import { useRouter } from "next/navigation"
import Image from "next/image"
import { Loader2, UserRound } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Alert, AlertDescription } from "@/components/ui/alert"
import ReplayViewer from "@/components/game/replay-viewer"
import { authAPI, gameAPI } from "@/lib/api"
import { buildAuthHref } from "@/lib/auth-redirect"
import { describeGameApiError } from "@/lib/game-errors"
import { memberParticipantId } from "@/lib/game-room"
import {
    describeGameTypeName,
    describeResultSubtitle,
    describeResultTitle,
    hasMoreResults,
    mergeResultPages,
} from "@/lib/replay-view"
import { useAuth } from "@/hooks/use-auth"
import type { GameResultSummary, GameType, MemberProfile } from "@/lib/types"

const PAGE_SIZE = 20
const MY_PAGE_PATH = "/mypage"

interface OpenReplay {
    gameResultId: number
    gameType: GameType
    url: string
}

/**
 * 세 번째 상단 탭. 프로필 · 전적 · 기보 다시보기.
 *
 * <p>회원 전용이다 — `/api/game/me/results` 와 `/api/game/results/{id}/replay` 는 둘 다
 * `GamePrincipals.require` 를 거치므로 게스트로는 아무것도 볼 수 없다. 로그인하지 않았으면
 * 여기로 돌아오는 `next` 를 달아 로그인 화면으로 보낸다.
 */
export default function MyPage() {
    const router = useRouter()
    const { loading, isAuthenticated } = useAuth()

    const [profile, setProfile] = useState<MemberProfile | null>(null)
    const [results, setResults] = useState<GameResultSummary[]>([])
    const [moreAvailable, setMoreAvailable] = useState(false)
    const [loadingMore, setLoadingMore] = useState(false)
    const [loadFailed, setLoadFailed] = useState(false)
    const [error, setError] = useState<string | null>(null)
    const [replay, setReplay] = useState<OpenReplay | null>(null)

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
        Promise.all([authAPI.me(), gameAPI.myResults(PAGE_SIZE, 0)])
            .then(([loadedProfile, loadedResults]) => {
                if (!active) {
                    return
                }
                setProfile(loadedProfile)
                setResults(loadedResults)
                setMoreAvailable(hasMoreResults(loadedResults.length, PAGE_SIZE))
            })
            .catch((cause) => {
                if (!active) {
                    return
                }
                // 스피너를 걷는다. 실패한 채로 계속 돌리면 "곧 됩니다" 라는 거짓말이 된다.
                setLoadFailed(true)
                setError(describeGameApiError(cause, "정보를 불러오지 못했습니다."))
            })

        return () => {
            active = false
        }
    }, [loading, isAuthenticated])

    const loadMore = useCallback(async () => {
        setLoadingMore(true)
        try {
            const page = await gameAPI.myResults(PAGE_SIZE, results.length)
            // 이어 붙이는 규칙은 mergeResultPages 가 안다 — offset 페이징은 목록 앞쪽에
            // 행이 늘면 이미 본 전적을 다시 준다(그러면 key 까지 충돌한다).
            setResults((current) => mergeResultPages(current, page))
            setMoreAvailable(hasMoreResults(page.length, PAGE_SIZE))
            setError(null)
        } catch (cause) {
            setError(describeGameApiError(cause, "전적을 더 불러오지 못했습니다."))
        } finally {
            setLoadingMore(false)
        }
    }, [results.length])

    const openReplay = useCallback(async (result: GameResultSummary) => {
        try {
            const url = await gameAPI.replayUrl(result.gameResultId)
            setReplay({ gameResultId: result.gameResultId, gameType: result.gameType, url })
            setError(null)
        } catch (cause) {
            setReplay(null)
            setError(describeGameApiError(cause, "기보를 불러오지 못했습니다."))
        }
    }, [])

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
                    <div className="flex items-center gap-4 rounded-lg border p-5">
                        <div className="relative flex h-16 w-16 shrink-0 items-center justify-center overflow-hidden rounded-full bg-muted">
                            {profile.profileImageUrl ? (
                                <Image
                                    src={profile.profileImageUrl}
                                    alt=""
                                    fill
                                    unoptimized
                                    sizes="64px"
                                    className="object-cover"
                                />
                            ) : (
                                <UserRound aria-hidden className="h-7 w-7 text-muted-foreground" />
                            )}
                        </div>
                        <div className="min-w-0">
                            <p className="truncate text-lg font-medium">{profile.nickname}</p>
                            <p className="truncate text-sm text-muted-foreground">{profile.email}</p>
                            <p className="mt-1 text-sm">
                                게임 머니 {profile.gameMoney.toLocaleString()}
                            </p>
                        </div>
                    </div>
                ) : null}
            </section>

            <section className="space-y-4">
                <h2 className="text-lg font-medium">전적</h2>

                {results.length === 0 ? (
                    <p className="text-sm text-muted-foreground">
                        {loadFailed ? "전적을 불러오지 못했습니다." : "아직 끝낸 게임이 없습니다."}
                    </p>
                ) : (
                    <ul className="space-y-2">
                        {results.map((result) => (
                            <li
                                key={result.gameResultId}
                                className="flex items-center justify-between gap-3 rounded-md border px-4 py-3 text-sm"
                            >
                                <div className="min-w-0">
                                    <p className="font-medium">{describeResultTitle(result)}</p>
                                    <p className="truncate text-xs text-muted-foreground">
                                        {describeResultSubtitle(result)}
                                    </p>
                                </div>
                                <Button
                                    size="sm"
                                    variant={
                                        replay?.gameResultId === result.gameResultId ? "secondary" : "outline"
                                    }
                                    // 기보 업로드는 결과 저장과 분리돼 있어 실패할 수 있다
                                    // (ReplayUploader 는 실패를 삼킨다). 그때 replayAvailable 이
                                    // false 로 온다 — 눌러 봐야 없는 것을 부르게 두지 않는다.
                                    disabled={!result.replayAvailable}
                                    onClick={() => openReplay(result)}
                                >
                                    다시보기
                                </Button>
                            </li>
                        ))}
                    </ul>
                )}

                {moreAvailable ? (
                    <div className="flex justify-center">
                        <Button variant="outline" size="sm" disabled={loadingMore} onClick={loadMore}>
                            {loadingMore ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
                            더 보기
                        </Button>
                    </div>
                ) : null}
            </section>

            {replay ? (
                <section className="space-y-3">
                    <h2 className="text-lg font-medium">
                        기보 다시보기 — {describeGameTypeName(replay.gameType)}
                    </h2>
                    <ReplayViewer
                        // 다른 전적을 열면 뷰어를 새로 만든다. 재생 위치·재생 중 여부가
                        // 이전 기보의 것으로 남아 있으면 안 된다.
                        key={replay.gameResultId}
                        gameType={replay.gameType}
                        replayUrl={replay.url}
                        selfParticipantId={memberParticipantId(profile?.memberId ?? null)}
                        onClose={() => setReplay(null)}
                    />
                </section>
            ) : null}
        </main>
    )
}
