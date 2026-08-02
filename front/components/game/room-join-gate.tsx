"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import Link from "next/link"
import { Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { tokenManager } from "@/lib/api"
import { assertNever } from "@/lib/game-errors"
import {
    GAME_TYPE_LABELS,
    NICKNAME_MAX_LENGTH,
    checkNickname,
    decideJoinGate,
    describeRoomOccupancy,
    joinRoomAsGuest,
    loadRoomSummary,
} from "@/lib/game-join"
import { useAuth } from "@/hooks/use-auth"
import type { GameType, RoomSummary } from "@/lib/types"

interface RoomJoinGateProps {
    roomId: string
    inviteCode: string
    expectedGameType: GameType
    onReady: (token: string) => void
}

/**
 * 초대 링크를 연 방문자가 게임 화면에 닿기 전에 통과하는 관문. 회원이면 이미 가지고 있는
 * access token 을, 아니면 닉네임으로 발급받은 게스트 토큰을 onReady 로 넘긴다. 판단은 전부
 * lib/game-join.ts 에 있고 여기서는 그리기와 리액트 수명주기만 다룬다.
 */
export default function RoomJoinGate({
    roomId,
    inviteCode,
    expectedGameType,
    onReady,
}: RoomJoinGateProps) {
    const { loading, isAuthenticated } = useAuth()
    const [summary, setSummary] = useState<RoomSummary | null>(null)
    // 방을 못 불러온 것은 되돌릴 수 없어 화면 전체를 안내로 덮지만, 참가 실패는 닉네임만
    // 고치면 되는 일이라 폼을 남긴 채 그 아래에 붙인다. 두 오류를 한 상태로 묶으면 오타 한
    // 번에 폼이 사라져 다시 시도할 방법이 없어진다.
    const [loadError, setLoadError] = useState<string | null>(null)
    const [joinError, setJoinError] = useState<string | null>(null)
    const [nickname, setNickname] = useState("")
    const [joining, setJoining] = useState(false)

    useEffect(() => {
        let cancelled = false
        setSummary(null)
        setLoadError(null)

        loadRoomSummary(roomId, inviteCode).then((outcome) => {
            if (cancelled) {
                return
            }
            if (outcome.kind === "summary") {
                setSummary(outcome.summary)
            } else {
                setLoadError(outcome.message)
            }
        })

        return () => {
            cancelled = true
        }
    }, [roomId, inviteCode])

    const stage = decideJoinGate({
        authLoading: loading,
        isAuthenticated,
        memberToken: tokenManager.getToken(),
        summary,
        expectedGameType,
        error: loadError,
    })

    // onReady 는 부모가 인라인 화살표 함수로 넘기기 쉬워 렌더마다 신원이 바뀐다. 이펙트
    // 의존성에 그대로 넣으면 렌더마다 다시 호출되므로, 최신 함수는 ref 로 들고 토큰이
    // 정해졌을 때 딱 한 번만 넘긴다.
    const onReadyRef = useRef(onReady)
    onReadyRef.current = onReady
    const deliveredRef = useRef(false)

    // ref 만 닫아 잡으므로 신원이 영원히 고정된다 — 아래 이펙트의 의존성으로 안전하다.
    const handOff = useCallback((token: string) => {
        if (deliveredRef.current) {
            return
        }
        deliveredRef.current = true
        onReadyRef.current(token)
    }, [])

    const memberToken = stage.kind === "member-ready" ? stage.token : null
    useEffect(() => {
        if (memberToken) {
            handOff(memberToken)
        }
    }, [memberToken, handOff])

    const joinAsGuest = async () => {
        setJoining(true)
        setJoinError(null)

        const outcome = await joinRoomAsGuest(roomId, inviteCode, nickname)
        if (outcome.kind === "token") {
            // joining 을 그대로 둔 채 부모가 게임 화면으로 갈아끼우게 한다.
            handOff(outcome.token)
            return
        }
        setJoinError(outcome.message)
        setJoining(false)
    }

    switch (stage.kind) {
        case "error":
            return <GateNotice message={stage.message} />
        case "wrong-game":
            return <GateNotice message="이 링크는 다른 게임의 방입니다." />
        case "loading":
        // 회원 토큰은 위 이펙트가 부모에게 넘겼다. 부모가 게임 화면으로 갈아끼울 때까지
        // 갈림길 대신 스피너를 보여 준다 — 로그인한 사람에게 로그인 버튼을 깜빡이지 않는다.
        case "member-ready":
            return (
                <div className="flex min-h-[50vh] items-center justify-center">
                    <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
                </div>
            )
        case "needs-identity":
            return (
                <div className="mx-auto max-w-md space-y-6 py-20">
                    <div className="space-y-1 text-center">
                        <h1 className="text-2xl font-semibold">
                            {GAME_TYPE_LABELS[stage.summary.gameType]} 방에 참가
                        </h1>
                        <p className="text-sm text-muted-foreground">
                            {describeRoomOccupancy(stage.summary)}
                        </p>
                    </div>

                    <div className="space-y-3">
                        <Button asChild className="w-full" variant="outline">
                            <Link href="/login">로그인하고 참가</Link>
                        </Button>

                        <div className="relative py-2 text-center text-xs uppercase text-muted-foreground">
                            또는
                        </div>

                        <Input
                            value={nickname}
                            onChange={(event) => setNickname(event.target.value)}
                            placeholder="닉네임 (필수)"
                            maxLength={NICKNAME_MAX_LENGTH}
                            disabled={joining}
                        />
                        <Button
                            className="w-full"
                            disabled={joining || !checkNickname(nickname).ok}
                            onClick={joinAsGuest}
                        >
                            {joining ? <Loader2 className="mr-2 h-4 w-4 animate-spin" /> : null}
                            닉네임으로 참가
                        </Button>

                        {joinError ? (
                            <Alert variant="destructive">
                                <AlertDescription>{joinError}</AlertDescription>
                            </Alert>
                        ) : null}
                    </div>
                </div>
            )
        default:
            return assertNever(stage)
    }
}

function GateNotice({ message }: { message: string }) {
    return (
        <div className="mx-auto max-w-md space-y-4 py-20 text-center">
            <p className="text-sm text-destructive">{message}</p>
            <Button asChild variant="outline">
                <Link href="/game">게임 목록으로</Link>
            </Button>
        </div>
    )
}
