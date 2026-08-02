"use client"

import { Suspense, useCallback, useEffect, useMemo, useReducer, useRef, useState } from "react"
import { useParams, useSearchParams } from "next/navigation"
import Link from "next/link"
import { ArrowDown, ArrowLeft, ArrowRight, ArrowUp, Loader2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Alert, AlertDescription } from "@/components/ui/alert"
import DodgeGrid, { DODGE_PLAYER_COLORS } from "@/components/game/dodge-grid"
import RoomJoinGate from "@/components/game/room-join-gate"
import RoomSidebar from "@/components/game/room-sidebar"
import { createGameSocket, type GameSocket, type SocketStatus } from "@/lib/game-socket"
import { clearStoredGuestToken, readStoredGuestIdentity, roomPath } from "@/lib/game-join"
import { describeSocketStatus, isSocketSettled, resolveSelfParticipantId } from "@/lib/game-room"
import {
    canMoveInDodge,
    describeDodgeOutcome,
    describeDodgeProgress,
    directionForKey,
    initialDodgeRoomState,
    isSelfEliminated,
    isTypingElement,
    reduceDodgeRoom,
    shouldSendMove,
    sortedRanks,
    toGridPlayers,
    toRoster,
    type SentMove,
} from "@/lib/dodge-play"
import type { DirectionName } from "@/lib/dodge-engine"
import { useAuth } from "@/hooks/use-auth"

/**
 * useSearchParams 를 쓰는 클라이언트 컴포넌트는 Suspense 경계 안에 있어야 한다 — 없으면
 * 정적 프리렌더 단계에서 next build 가 이 페이지에서 멈춘다. 오목 화면과 같다.
 */
export default function DodgeRoomPage() {
    return (
        <Suspense fallback={<ScreenSpinner />}>
            <DodgeRoomScreen />
        </Suspense>
    )
}

function DodgeRoomScreen() {
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

    // 소켓 메시지를 프레임·명단으로 접는 일은 전부 lib/dodge-play.ts 의 리듀서가 한다.
    const [state, dispatch] = useReducer(reduceDodgeRoom, initialDodgeRoomState)

    const socketRef = useRef<GameSocket | null>(null)
    // 키 입력 핸들러가 읽는 최신 상태. 틱은 초당 10번 바뀌는데 그때마다 window 리스너를
    // 다시 붙이고 싶지는 않으므로, 핸들러는 상태를 클로저가 아니라 이 ref 에서 읽는다.
    const stateRef = useRef(state)
    // 마지막으로 실제로 나간 이동. shouldSendMove 의 주석 참고 — send 가 null 을 돌려준
    // (=버려진) 프레임은 여기 기록하지 않는다.
    const lastSentMoveRef = useRef<SentMove | null>(null)

    useEffect(() => {
        stateRef.current = state
    }, [state])

    // 초대 링크의 모양을 아는 곳은 roomPath 하나뿐이다 — 여기서 다시 조립하면 인코딩이
    // 어긋나 로그인 후 돌아오는 경로와 달라진다.
    const inviteUrl = useMemo(() => {
        if (typeof window === "undefined") {
            return ""
        }
        return `${window.location.origin}${roomPath("DODGE", roomId, inviteCode)}`
    }, [roomId, inviteCode])

    // 게이트가 게스트 토큰을 발급받아 저장한 뒤에 읽어야 하므로 token 을 의존성에 둔다.
    useEffect(() => {
        setGuestParticipantId(readStoredGuestIdentity(roomId)?.participantId ?? null)
    }, [roomId, token])

    useEffect(() => {
        if (!token) {
            return
        }

        // 방이 바뀌면 이전 방의 프레임을 남기지 않는다. App Router 는 동적 세그먼트만 바뀔 때
        // 이 컴포넌트를 다시 마운트하지 않는다.
        dispatch({ type: "reset" })
        setSocketStatus("connecting")
        setSocketErrorCode(undefined)
        lastSentMoveRef.current = null

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

    /**
     * 거절당한 게스트 토큰은 버린다. 오목 화면과 같은 이유다 — 게스트 토큰은 6시간 TTL 로
     * sessionStorage 에 남아 새로고침 복구에 쓰이는데, 그보다 먼저 죽는 경우(방이 정리됐다,
     * 서버가 재시작해 Redis 항목이 사라졌다)를 알 수 있는 지점은 소켓 JOIN 이 거절되는
     * 여기뿐이다. 지우지 않으면 새로고침할 때마다 게이트가 죽은 토큰을 다시 꺼내 소켓에
     * 물리고, 게스트는 닉네임 폼으로 돌아갈 길이 영영 없다.
     */
    useEffect(() => {
        if (socketStatus === "rejected") {
            clearStoredGuestToken(roomId)
        }
    }, [socketStatus, roomId])

    const selfParticipantId = resolveSelfParticipantId({
        memberId,
        guestParticipantId,
        participants: state.participants,
    })

    /**
     * 이동 한 번. 키보드와 화면 방향 버튼이 같은 문을 쓴다.
     *
     * <p>send 는 소켓이 열려 있지 않으면 프레임을 버리고 null 을 돌려준다. 그 경우를
     * "보냈다" 로 기록하면 같은 틱 동안 같은 방향이 막혀, 재접속 직후의 첫 입력이 조용히
     * 사라진다. 숫자를 돌려받았을 때만 기록한다.
     */
    const move = useCallback(
        (direction: DirectionName) => {
            const snapshot = stateRef.current
            if (!canMoveInDodge(snapshot, selfParticipantId, socketStatus)) {
                return
            }
            const next: SentMove = { tick: snapshot.tick, direction }
            if (!shouldSendMove(lastSentMoveRef.current, next)) {
                return
            }
            const seq = socketRef.current?.send("DODGE_MOVE", { direction })
            if (typeof seq === "number") {
                lastSentMoveRef.current = next
            }
        },
        [selfParticipantId, socketStatus]
    )

    useEffect(() => {
        // 판이 돌고 있고 서버가 우리를 방에 넣어 준 동안에만 키보드를 잡는다.
        if (socketStatus !== "joined" || state.status !== "IN_PROGRESS") {
            return
        }

        const onKeyDown = (event: KeyboardEvent) => {
            // 단축키(Ctrl+←로 워크스페이스 전환 등)를 가로채지 않는다.
            if (event.ctrlKey || event.metaKey || event.altKey) {
                return
            }
            const direction = directionForKey(event.key)
            if (!direction) {
                return
            }
            // 사이드바의 초대 링크 입력칸 위에서 누른 방향키는 캐럿의 것이다.
            if (isTypingElement(event.target as HTMLElement | null)) {
                return
            }
            if (!canMoveInDodge(stateRef.current, selfParticipantId, socketStatus)) {
                // 탈락한 관전자의 방향키까지 막으면 페이지 스크롤이 안 된다. 우리가 쓰지
                // 않을 키는 브라우저에 그대로 돌려준다.
                return
            }
            // 방향키의 기본 동작은 페이지 스크롤이다. 판이 화면 밖으로 밀려나면 조작할 수 없다.
            event.preventDefault()
            move(direction)
        }

        window.addEventListener("keydown", onKeyDown)
        return () => window.removeEventListener("keydown", onKeyDown)
    }, [socketStatus, state.status, selfParticipantId, move])

    if (!token) {
        return (
            <RoomJoinGate
                roomId={roomId}
                inviteCode={inviteCode}
                expectedGameType="DODGE"
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

    const connectionNotice = describeSocketStatus(socketStatus, socketErrorCode)
    const eliminated = isSelfEliminated(state, selfParticipantId)
    const players = toGridPlayers(state, selfParticipantId)
    const roster = toRoster(state, selfParticipantId)
    const statusLine = state.outcome
        ? describeDodgeOutcome(state.outcome, selfParticipantId)
        : describeDodgeProgress(state, eliminated)
    const canMove = canMoveInDodge(state, selfParticipantId, socketStatus)

    return (
        <main className="mx-auto flex w-full max-w-7xl flex-col gap-6 px-4 py-6 lg:flex-row lg:items-start">
            <div className="flex min-w-0 flex-1 flex-col items-center gap-3">
                {connectionNotice ? (
                    // 종단 상태에는 스피너를 달지 않는다. 소켓은 다시 붙지 않는데 돌아가는
                    // 스피너는 "곧 됩니다" 라고 말한다.
                    <Alert
                        className="w-full max-w-[min(100%,60vh)]"
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

                <DodgeGrid players={players} obstacles={state.obstacles} />

                <div className="flex min-h-5 flex-wrap items-center justify-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
                    {statusLine ? <span>{statusLine}</span> : null}
                    {state.notice ? <span className="text-destructive">{state.notice}</span> : null}
                </div>

                {/* 색·번호와 이름을 잇는 표. 이것이 없으면 점 여덟 개가 누가 누구인지 알 수 없다. */}
                {roster.length > 0 ? (
                    <ul className="flex w-full max-w-[min(100%,60vh)] flex-wrap justify-center gap-x-3 gap-y-1 text-xs">
                        {roster.map((entry) => (
                            <li
                                key={entry.participantId}
                                className={
                                    entry.alive
                                        ? "flex items-center gap-1.5"
                                        : "flex items-center gap-1.5 text-muted-foreground line-through"
                                }
                            >
                                <span
                                    aria-hidden
                                    className={[
                                        "inline-flex h-4 w-4 items-center justify-center rounded-full",
                                        "text-[0.55rem] font-bold leading-none text-white",
                                        DODGE_PLAYER_COLORS[entry.colorIndex % DODGE_PLAYER_COLORS.length],
                                        entry.alive ? "" : "opacity-40",
                                    ].join(" ")}
                                >
                                    {entry.colorIndex + 1}
                                </span>
                                <span className="max-w-[8rem] truncate">
                                    {entry.displayName}
                                    {entry.isSelf ? " (나)" : ""}
                                </span>
                            </li>
                        ))}
                    </ul>
                ) : null}

                {/* 터치 기기에는 키보드가 없다. 좁은 화면에서만 방향 패드를 띄운다. */}
                {canMove ? (
                    <div className="grid grid-cols-3 gap-1 lg:hidden" aria-label="이동">
                        <span />
                        <DirectionButton label="위로" onPress={() => move("UP")}>
                            <ArrowUp className="h-5 w-5" />
                        </DirectionButton>
                        <span />
                        <DirectionButton label="왼쪽으로" onPress={() => move("LEFT")}>
                            <ArrowLeft className="h-5 w-5" />
                        </DirectionButton>
                        <DirectionButton label="아래로" onPress={() => move("DOWN")}>
                            <ArrowDown className="h-5 w-5" />
                        </DirectionButton>
                        <DirectionButton label="오른쪽으로" onPress={() => move("RIGHT")}>
                            <ArrowRight className="h-5 w-5" />
                        </DirectionButton>
                    </div>
                ) : null}

                {state.outcome ? (
                    <ol className="w-full max-w-[min(100%,60vh)] space-y-1 text-sm">
                        {sortedRanks(state.outcome).map((entry) => (
                            <li
                                key={entry.participantId}
                                className="flex items-center justify-between rounded-md border px-3 py-1.5"
                            >
                                <span className="truncate">
                                    {entry.displayName}
                                    {entry.participantId === selfParticipantId ? " (나)" : ""}
                                </span>
                                <span className="text-muted-foreground">{entry.rank}위</span>
                            </li>
                        ))}
                    </ol>
                ) : null}
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

function DirectionButton({
    label,
    onPress,
    children,
}: {
    label: string
    onPress: () => void
    children: React.ReactNode
}) {
    return (
        <Button
            type="button"
            size="icon"
            variant="outline"
            aria-label={label}
            // 클릭이 아니라 눌린 순간에 보낸다. 100ms 틱에서 클릭의 왕복(누름→뗌)을 기다리면
            // 한 틱을 통째로 놓친다.
            onPointerDown={(event) => {
                // 버튼이 포커스를 가져가면 그다음 방향키가 이 버튼의 것이 된다(스페이스/엔터
                // 로 다시 눌리는 것도 포함). 키보드 조작과 섞이지 않게 포커스를 주지 않는다.
                event.preventDefault()
                onPress()
            }}
        >
            {children}
        </Button>
    )
}

function ScreenSpinner() {
    return (
        <div className="flex min-h-[50vh] items-center justify-center">
            <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
        </div>
    )
}
