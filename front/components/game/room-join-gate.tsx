"use client"

import { useCallback, useEffect, useRef, useState } from "react"
import Link from "next/link"
import { Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { tokenManager } from "@/lib/api"
import { buildAuthHref } from "@/lib/auth-redirect"
import { assertNever } from "@/lib/game-errors"
import {
    GAME_TYPE_LABELS,
    NICKNAME_MAX_LENGTH,
    checkNickname,
    decideJoinGate,
    describeRoomOccupancy,
    joinRoomAsGuest,
    loadRoomSummary,
    readStoredGuestToken,
    roomPath,
    type JoinTokenSource,
} from "@/lib/game-join"
import { useAuth } from "@/hooks/use-auth"
import type { GameType, RoomSummary } from "@/lib/types"

interface RoomJoinGateProps {
    roomId: string
    inviteCode: string
    expectedGameType: GameType
    /**
     * 토큰과 함께 <b>출처</b>를 넘긴다. 게임 화면은 출처 없이는 거절을 옳게 다룰 수 없다 —
     * 죽은 회원 토큰은 버리고 게이트로 돌아가야 하고(C1), 게스트 토큰은 재접속마다
     * sessionStorage 에서 다시 읽어야 한다(I7). 문자열만으로는 둘을 구분할 수 없다.
     */
    onReady: (token: string, source: JoinTokenSource) => void
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
    // 같은 탭에서 이 방의 게스트 토큰을 이미 받아 뒀는지. 렌더 중에 sessionStorage 를 읽지
    // 않는 이유는 readStoredGuestToken 이 만료·손상된 항목을 지우기 때문이다 — 렌더에서
    // 하는 쓰기는 StrictMode 나 프리렌더가 버릴 수 있다.
    const [storedGuestToken, setStoredGuestToken] = useState<string | null>(null)
    // 위 상태들이 어느 방의 것인지. 프롭이 바뀐 렌더에서는 아직 이전 방을 가리킨다.
    const [loadedRoom, setLoadedRoom] = useState<string | null>(null)

    // onReady 는 부모가 인라인 화살표 함수로 넘기기 쉬워 렌더마다 신원이 바뀐다. 부모는
    // 받은 토큰을 setState 할 것이므로, 이펙트 의존성에 그대로 넣으면 호출 → 리렌더 →
    // 새 함수 → 재호출의 루프가 된다. 최신 함수는 ref 로 들고 방마다 한 번만 넘긴다.
    const onReadyRef = useRef(onReady)
    useEffect(() => {
        onReadyRef.current = onReady
    })

    // 방 단위로 기억한다. 불리언이면 다음 방으로 넘어갈 때 영원히 잠긴다.
    const deliveredRoomRef = useRef<string | null>(null)

    useEffect(() => {
        let cancelled = false
        // 방이 바뀌면 이전 방에 대해 했던 모든 판단을 버린다. App Router 는 동적 세그먼트만
        // 바뀔 때 이 컴포넌트를 다시 마운트하지 않으므로, 여기서 지우지 않으면 이전 방의
        // 상태가 그대로 남는다.
        deliveredRoomRef.current = null
        setSummary(null)
        setLoadError(null)
        setJoinError(null)
        setJoining(false)
        setNickname("")
        setStoredGuestToken(readStoredGuestToken(roomId))
        setLoadedRoom(roomId)

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
        roomId,
        loadedRoom,
        authLoading: loading,
        isAuthenticated,
        memberToken: tokenManager.getToken(),
        storedGuestToken,
        summary,
        expectedGameType,
        error: loadError,
    })

    /** 넘겼으면 true, 이미 이 방에 대해 넘긴 뒤라 아무것도 하지 않았으면 false. */
    const handOff = useCallback((room: string, token: string, source: JoinTokenSource) => {
        if (deliveredRoomRef.current === room) {
            return false
        }
        deliveredRoomRef.current = room
        onReadyRef.current(token, source)
        return true
    }, [])

    // roomId 를 의존성에 넣어야 한다. 회원 토큰 문자열은 방이 바뀌어도 그대로라서
    // 토큰만 보고 있으면 두 번째 방에서는 이펙트가 다시 돌지 않는다.
    const readyToken = stage.kind === "ready" ? stage.token : null
    const readySource = stage.kind === "ready" ? stage.source : null
    useEffect(() => {
        if (readyToken && readySource) {
            handOff(roomId, readyToken, readySource)
        }
    }, [roomId, readyToken, readySource, handOff])

    const joinAsGuest = async () => {
        setJoining(true)
        setJoinError(null)

        const outcome = await joinRoomAsGuest(roomId, inviteCode, nickname)
        if (outcome.kind === "error") {
            setJoinError(outcome.message)
            setJoining(false)
            return
        }

        // joinRoomAsGuest 가 sessionStorage 에 넣어 뒀다. 화면 상태도 맞춰 둬야 게이트가
        // 계속 마운트된 채 다시 렌더되더라도 갈림길로 되돌아가지 않는다.
        setStoredGuestToken(outcome.token)

        // 넘겼으면 joining 을 그대로 둔 채 부모가 게임 화면으로 갈아끼우게 한다. 넘기지
        // 못했다면 스피너를 끄고 이유를 말한다 — 아무 말 없이 계속 도는 버튼은 막다른 길이다.
        if (!handOff(roomId, outcome.token, "guest-session")) {
            setJoinError("이미 이 방에 참가했습니다. 페이지를 새로고침해 주세요.")
            setJoining(false)
        }
    }

    switch (stage.kind) {
        case "error":
            return <GateNotice message={stage.message} />
        case "wrong-game":
            return <GateNotice message="이 링크는 다른 게임의 방입니다." />
        case "loading":
        // 토큰은 위 이펙트가 부모에게 넘겼다. 부모가 게임 화면으로 갈아끼울 때까지 갈림길
        // 대신 스피너를 보여 준다 — 로그인한 사람에게 로그인 버튼을 깜빡이지 않는다.
        case "ready":
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

                    {stage.block ? (
                        <Alert variant="destructive">
                            <AlertDescription>{stage.block.message}</AlertDescription>
                        </Alert>
                    ) : null}

                    <div className="space-y-3">
                        {/*
                          * 방이 막혀 있어도 로그인 링크는 남긴다. 이 방에 이미 자리가 있는
                          * 회원이 새로고침 뒤 돌아오는 길이 이것뿐이고, 그 경우 서버는
                          * RECONNECTED 로 받아 준다. 확실히 새 참가자인 게스트 폼만 잠근다.
                          *
                          * 돌아올 곳(next)을 함께 실어 보낸다. 이 링크를 누르는 사람은 정의상
                          * 로그인하지 않았고, 방 id 와 초대 코드는 지금 떠나는 URL 에만 있다 —
                          * 넘기지 않으면 로그인을 마친 순간 초대가 사라진다.
                          */}
                        <Button asChild className="w-full" variant="outline">
                            <Link
                                href={buildAuthHref(
                                    "/login",
                                    roomPath(stage.summary.gameType, roomId, inviteCode)
                                )}
                            >
                                로그인하고 참가
                            </Link>
                        </Button>

                        <div className="relative py-2 text-center text-xs uppercase text-muted-foreground">
                            또는
                        </div>

                        <Input
                            value={nickname}
                            // maxLength 는 trim 전에 걸리므로 앞쪽 공백이 20자를 갉아먹는다.
                            // 앞 공백은 애초에 넣지 않는다.
                            onChange={(event) => setNickname(event.target.value.replace(/^\s+/, ""))}
                            placeholder="닉네임 (필수)"
                            maxLength={NICKNAME_MAX_LENGTH}
                            disabled={joining || stage.block !== null}
                        />
                        <Button
                            className="w-full"
                            disabled={joining || stage.block !== null || !checkNickname(nickname).ok}
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
