"use client"

import { Suspense, useEffect, useMemo, useReducer, useRef, useState } from "react"
import { useParams, useSearchParams } from "next/navigation"
import Link from "next/link"
import { Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Alert, AlertDescription } from "@/components/ui/alert"
import OmokBoard from "@/components/game/omok-board"
import RoomJoinGate from "@/components/game/room-join-gate"
import RoomSidebar from "@/components/game/room-sidebar"
import { createGameSocket, type GameSocket, type SocketStatus } from "@/lib/game-socket"
import { discardGuestTokenOnRejection, readStoredGuestIdentity, roomPath } from "@/lib/game-join"
import {
    OMOK_BOARD_SIZE,
    canPlaceStone,
    describeGameOutcome,
    describeTurn,
    initialOmokRoomState,
    myStoneColor,
    reduceOmokRoom,
} from "@/lib/omok-play"
import {
    describeSocketStatus,
    isSocketSettled,
    resolveSelfParticipantId,
} from "@/lib/game-room"
import { useAuth } from "@/hooks/use-auth"

/**
 * useSearchParams 를 쓰는 클라이언트 컴포넌트는 Suspense 경계 안에 있어야 한다 — 없으면
 * 정적 프리렌더 단계에서 next build 가 이 페이지에서 멈춘다. app/login/page.tsx 와 같다.
 */
export default function OmokRoomPage() {
    return (
        <Suspense fallback={<ScreenSpinner />}>
            <OmokRoomScreen />
        </Suspense>
    )
}

function OmokRoomScreen() {
    const params = useParams<{ roomId: string }>()
    const searchParams = useSearchParams()
    const roomId = params.roomId
    const inviteCode = searchParams.get("invite") ?? ""

    const { memberId } = useAuth()

    // 게이트가 넘겨 준 토큰. 이것이 생기기 전에는 소켓을 열지 않는다.
    const [token, setToken] = useState<string | null>(null)
    const [socketStatus, setSocketStatus] = useState<SocketStatus>("connecting")
    const [socketErrorCode, setSocketErrorCode] = useState<string | undefined>(undefined)
    // 이 방의 게스트 토큰과 함께 저장된 participantId. sessionStorage 는 렌더 중에 읽지
    // 않는다(서버 렌더에는 없고, 하이드레이션이 어긋난다).
    const [guestParticipantId, setGuestParticipantId] = useState<string | null>(null)

    // 소켓 메시지를 판·명단·차례로 접는 일은 전부 lib/omok-play.ts 의 리듀서가 한다.
    const [state, dispatch] = useReducer(reduceOmokRoom, initialOmokRoomState)

    const socketRef = useRef<GameSocket | null>(null)

    // 초대 링크의 모양을 아는 곳은 roomPath 하나뿐이다 — 여기서 다시 조립하면 인코딩이
    // 어긋나 로그인 후 돌아오는 경로와 달라진다.
    const inviteUrl = useMemo(() => {
        if (typeof window === "undefined") {
            return ""
        }
        return `${window.location.origin}${roomPath("OMOK", roomId, inviteCode)}`
    }, [roomId, inviteCode])

    // 게이트가 게스트 토큰을 발급받아 저장한 뒤에 읽어야 하므로 token 을 의존성에 둔다.
    useEffect(() => {
        setGuestParticipantId(readStoredGuestIdentity(roomId)?.participantId ?? null)
    }, [roomId, token])

    useEffect(() => {
        if (!token) {
            return
        }

        // 방이 바뀌면 이전 방의 판을 남기지 않는다. App Router 는 동적 세그먼트만 바뀔 때
        // 이 컴포넌트를 다시 마운트하지 않는다.
        dispatch({ type: "reset" })
        setSocketStatus("connecting")
        setSocketErrorCode(undefined)

        // 버린 소켓의 뒤늦은 콜백을 막는다. close() 는 곧바로 종료 상태를 알리는 것이
        // 아니라 onclose 를 기다리므로, 방을 옮기거나 StrictMode 가 이펙트를 두 번 돌릴 때
        // 새 소켓이 이미 connecting 을 올린 뒤에 옛 소켓의 "closed" 가 도착해 그 위를
        // 덮어쓴다 — 멀쩡히 붙는 중인 화면에 "연결이 끊어졌습니다" 가 뜨는 경로다.
        let active = true

        const socket = createGameSocket({
            roomId,
            inviteCode,
            token,
            onMessage: (message) => {
                if (active) {
                    dispatch({ type: "message", message })
                }
            },
            onStatusChange: (status, errorCode) => {
                if (!active) {
                    return
                }
                setSocketStatus(status)
                setSocketErrorCode(errorCode)
            },
        })
        socketRef.current = socket

        return () => {
            active = false
            socket.close()
            socketRef.current = null
        }
    }, [token, roomId, inviteCode])

    // 거절당한 게스트 토큰은 버린다. 어떤 상태에서 버려야 하는지는 discardGuestTokenOnRejection
    // 이 안다 — 그 판단이 이 이펙트 안에 있으면 테스트가 닿지 않는다.
    useEffect(() => {
        discardGuestTokenOnRejection(roomId, socketStatus)
    }, [socketStatus, roomId])

    const selfParticipantId = resolveSelfParticipantId({
        memberId,
        guestParticipantId,
        participants: state.participants,
    })

    if (!token) {
        return (
            <RoomJoinGate
                roomId={roomId}
                inviteCode={inviteCode}
                expectedGameType="OMOK"
                onReady={setToken}
            />
        )
    }

    // 참가가 거절되면 소켓은 다시 붙지 않는다(종단 상태). 판을 그대로 두면 아무것도 반응하지
    // 않는 죽은 화면이 되므로 이유와 빠져나갈 길을 대신 보여 준다. 토큰 상태를 되돌려 게이트로
    // 보내지는 않는다 — 회원 토큰은 게이트가 곧바로 다시 넘겨줘 거절 루프가 된다.
    if (socketStatus === "rejected") {
        return (
            <main className="mx-auto max-w-md space-y-4 py-20 text-center">
                <p className="text-sm text-destructive">
                    {describeSocketStatus("rejected", socketErrorCode)}
                </p>
                <div className="flex justify-center gap-2">
                    <Button variant="outline" onClick={() => window.location.reload()}>
                        다시 참가
                    </Button>
                    <Button asChild variant="outline">
                        <Link href="/game">게임 목록으로</Link>
                    </Button>
                </div>
            </main>
        )
    }

    const myStone = myStoneColor(state, selfParticipantId)
    const connectionNotice = describeSocketStatus(socketStatus, socketErrorCode)
    // 응답을 기다리는 착수가 있는 동안에는 판을 닫는다. 빈 칸 두 곳을 빠르게 연달아 누르면
    // 둘 다 나가고 두 번째가 pendingPlaceSeq 를 덮어써, 첫 번째의 거부 안내가 조용히 사라진다.
    // 서버는 모든 OMOK_PLACE 에 응답하므로(착수·거부·오류) 이 잠금은 왕복 한 번만 지속된다.
    const awaitingPlace = state.pendingPlaceSeq !== null
    const myTurn = canPlaceStone(state, selfParticipantId, socketStatus)
    // 차례 안내는 notice 와 자리를 다투지 않는다. 금수를 뒀을 때 거부 안내가 차례 표시를
    // 통째로 밀어내면, 여전히 내 차례인데 화면이 그 말을 멈춘다.
    const statusLine = state.outcome
        ? describeGameOutcome(state.outcome, selfParticipantId)
        : describeTurn(state, state.turnParticipantId === selfParticipantId)

    const place = (x: number, y: number) => {
        if (awaitingPlace) {
            return
        }
        // send 는 소켓이 열려 있지 않으면 프레임을 버리고 null 을 돌려준다. 그 경우 응답도
        // 오지 않으므로 잠금을 걸면 안 된다 — 걸면 다음 착수까지 막힌다.
        const seq = socketRef.current?.send("OMOK_PLACE", { x, y })
        if (typeof seq === "number") {
            dispatch({ type: "place-sent", seq })
        }
    }

    return (
        <main className="mx-auto flex w-full max-w-7xl flex-col gap-6 px-4 py-6 lg:flex-row lg:items-start">
            <div className="flex min-w-0 flex-1 flex-col items-center gap-3">
                {connectionNotice ? (
                    // 종단 상태에는 스피너를 달지 않는다. 소켓은 다시 붙지 않는데 돌아가는
                    // 스피너는 "곧 됩니다" 라고 말한다.
                    <Alert
                        className="w-full max-w-[min(100%,80vh)]"
                        variant={isSocketSettled(socketStatus) ? "destructive" : "default"}
                    >
                        <AlertDescription className="flex items-center gap-2 text-sm">
                            {isSocketSettled(socketStatus) ? null : (
                                <Loader2 className="h-4 w-4 shrink-0 animate-spin" />
                            )}
                            {connectionNotice}
                        </AlertDescription>
                    </Alert>
                ) : null}

                <OmokBoard
                    size={OMOK_BOARD_SIZE}
                    placements={state.placements}
                    disabled={!myTurn || awaitingPlace}
                    onPlace={place}
                />

                <div className="flex min-h-5 flex-wrap items-center justify-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
                    {myStone ? (
                        <span className="flex items-center gap-1.5">
                            <span
                                aria-hidden
                                className={
                                    myStone === "BLACK"
                                        ? "inline-block h-3 w-3 rounded-full bg-neutral-900"
                                        : "inline-block h-3 w-3 rounded-full border border-neutral-400 bg-white"
                                }
                            />
                            내 돌 {myStone === "BLACK" ? "흑" : "백"}
                        </span>
                    ) : null}
                    {statusLine ? <span>{statusLine}</span> : null}
                    {state.notice ? <span className="text-destructive">{state.notice}</span> : null}
                </div>
            </div>

            <RoomSidebar
                participants={state.participants}
                hostParticipantId={state.hostParticipantId}
                status={state.status}
                inviteUrl={inviteUrl}
                selfParticipantId={selfParticipantId}
                onReadyToggle={(ready) => socketRef.current?.send("READY", { ready })}
                onStart={() => socketRef.current?.send("START")}
            />
        </main>
    )
}

function ScreenSpinner() {
    return (
        <div className="flex min-h-[50vh] items-center justify-center">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
    )
}
