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
import {
    discardGuestTokenOnRejection,
    discardMemberTokenOnRejection,
    readStoredGuestIdentity,
    resolveJoinToken,
    roomPath,
    shouldDiscardMemberToken,
    type JoinTokenSource,
} from "@/lib/game-join"
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
import { useVerifiedMemberId } from "@/hooks/use-verified-member-id"

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

    // 저장해 둔 authMemberId 대신 게임 서버가 토큰에서 뽑아 준 값을 쓴다(회원일 때만).
    // 소켓 JOIN 을 통과시키는 검증기와 같은 것이라 신원이 어긋날 수 없다.
    const { memberId: storedMemberId } = useAuth()
    const memberId = useVerifiedMemberId(storedMemberId)

    // 게이트가 넘겨 준 토큰과 그 출처. 이것이 생기기 전에는 소켓을 열지 않는다.
    // 출처가 필요한 이유는 두 가지다: 죽은 회원 토큰을 버리고 게이트로 되돌려 보내는 판단(C1)과,
    // 재접속마다 어느 저장소에서 토큰을 다시 읽을지(I7).
    const [token, setToken] = useState<string | null>(null)
    const [tokenSource, setTokenSource] = useState<JoinTokenSource | null>(null)
    const [socketStatus, setSocketStatus] = useState<SocketStatus>("connecting")
    const [socketErrorCode, setSocketErrorCode] = useState<string | undefined>(undefined)
    // 이 방의 게스트 토큰과 함께 저장된 participantId. sessionStorage 는 렌더 중에 읽지
    // 않는다(서버 렌더에는 없고, 하이드레이션이 어긋난다).
    const [guestParticipantId, setGuestParticipantId] = useState<string | null>(null)

    // 소켓 메시지를 판·명단·차례로 접는 일은 전부 lib/omok-play.ts 의 리듀서가 한다.
    const [state, dispatch] = useReducer(reduceOmokRoom, initialOmokRoomState)

    const socketRef = useRef<GameSocket | null>(null)
    // 소켓 이펙트의 의존성에서 초대 코드를 뺀 대신 여기로 읽는다 — 위 이펙트의 설명 참고.
    // 렌더 중 대입은 안전하다: 이 ref 는 렌더 결과에 쓰이지 않고, JOIN 시점에만 읽힌다.
    const inviteCodeRef = useRef(inviteCode)
    inviteCodeRef.current = inviteCode

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

    /**
     * I3: 소켓의 수명을 다루는 이펙트는 <b>이것 하나뿐</b>이다.
     *
     * <p>처음에는 LEAVE 를 별도 이펙트의 정리 함수에 두고 "소켓 이펙트보다 먼저 선언해야
     * 한다"고 주석으로 못 박았다. 정리 함수는 선언 순서대로 도니까 뒤에 두면 소켓이 먼저
     * 닫히고 socketRef 가 비기 때문인데 — 그 규칙을 어겨도 타입 검사도 테스트도 빌드도
     * 전부 통과했다. 즉 30초 유령 버그가 이펙트 두 개의 순서를 바꾸는 것만으로 조용히
     * 되살아났다. 주석으로 지키는 불변식이었다.
     *
     * <p>그래서 순서가 <b>존재할 수 없게</b> 고쳤다. 정리 함수가 하나뿐이니 순서를 틀릴 수
     * 없고, {@link GameSocket} 에는 종료 방법이 `leave()` 하나뿐이니 조용히 닫는 쪽을 고를
     * 수도 없다.
     *
     * <p>그러려면 이 이펙트가 도는 이유가 전부 "정말 떠나는 것" 이어야 한다. 의존성이
     * `[token, tokenSource, roomId]` 인 것은 그 때문이다.
     * <ul>
     *   <li>`roomId` 변경·언마운트 — 진짜 이탈. LEAVE 가 맞다.
     *   <li>`token` 이 null→값 — 게이트의 인계. 그 전에는 소켓이 없었으므로 정리 자체가 없다.
     *   <li>`token` 이 값→null — C1 이 죽은 토큰을 버린 경우. 그 소켓은 이미 종단
     *       `rejected` 라 참가가 확정된 적이 없고, `leave()` 는 그때 프레임을 보내지 않는다.
     *   <li>`inviteCode` — <b>일부러 뺐다.</b> 초대 코드만 바뀌는 것은 이탈이 아닌데
     *       의존성에 두면 LEAVE 가 나가 진행 중인 판에서 자리를 잃는다. 소켓에는 공급자로
     *       넘겨, 다음 JOIN 이 최신 값을 싣는다.
     * </ul>
     *
     * <p>StrictMode 의 이중 호출도 안전하다. 마운트 직후에는 게이트가 아직 토큰을 넘기기
     * 전이라 소켓이 없고, 있더라도 `leave()` 는 참가가 확정된(첫 ROOM_STATE 가 도착한)
     * 연결에서만 프레임을 보낸다 — 그 왕복이 동기적으로 끝날 수는 없다.
     */
    useEffect(() => {
        if (!token || !tokenSource) {
            return
        }

        // 방이 바뀌면 이전 방의 판을 남기지 않는다. App Router 는 동적 세그먼트만 바뀔 때
        // 이 컴포넌트를 다시 마운트하지 않는다.
        dispatch({ type: "reset" })
        setSocketStatus("connecting")
        setSocketErrorCode(undefined)

        // 버린 소켓의 뒤늦은 콜백을 막는다. leave() 는 곧바로 종료 상태를 알리는 것이
        // 아니라 onclose 를 기다리므로, 방을 옮기거나 StrictMode 가 이펙트를 두 번 돌릴 때
        // 새 소켓이 이미 connecting 을 올린 뒤에 옛 소켓의 "closed" 가 도착해 그 위를
        // 덮어쓴다 — 멀쩡히 붙는 중인 화면에 "연결이 끊어졌습니다" 가 뜨는 경로다.
        let active = true

        const socket = createGameSocket({
            roomId,
            // 초대 코드도 토큰도 공급자로 넘긴다. 재접속 JOIN 은 그때그때의 값을 실어야 하고,
            // 문자열로 고정하면 액세스 토큰의 TTL 이 판 도중에 지나는 순간부터 모든 재접속이
            // 종단 rejected 가 된다(I7).
            inviteCode: () => inviteCodeRef.current,
            token: () => resolveJoinToken(roomId, tokenSource, token),
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
            socket.leave()
            socketRef.current = null
        }
    }, [token, tokenSource, roomId])

    // 거절당한 게스트 토큰은 버린다. 어떤 상태에서 버려야 하는지는 discardGuestTokenOnRejection
    // 이 안다 — 그 판단이 이 이펙트 안에 있으면 테스트가 닿지 않는다.
    useEffect(() => {
        discardGuestTokenOnRejection(roomId, socketStatus)
    }, [socketStatus, roomId])

    // C1: 죽은 회원 토큰으로 거절당했다면 그 토큰을 버리고 게이트로 되돌아간다. 종단 패널에
    // 남겨 두면 "다시 참가" 버튼이 새로고침으로 같은 거절을 재현할 뿐이고, 게스트 갈림길에
    // 닿을 방법이 없다. 어떤 거절이 그에 해당하는지, 그리고 무엇을 지울지(액세스 토큰만이다 —
    // 리프레시 토큰까지 지우면 blog·auth 세션까지 조용히 끊긴다)는 game-join.ts 가 안다.
    const rejection = {
        source: tokenSource,
        status: socketStatus,
        errorCode: socketErrorCode,
    }
    const memberTokenIsDead = shouldDiscardMemberToken(rejection)

    useEffect(() => {
        if (!discardMemberTokenOnRejection(rejection)) {
            return
        }
        setToken(null)
        setTokenSource(null)
        // rejection 은 매 렌더 새 객체다. 판정 결과만 의존성에 둔다.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [memberTokenIsDead])

    const selfParticipantId = resolveSelfParticipantId({
        memberId,
        guestParticipantId,
        participants: state.participants,
    })

    // 토큰이 죽은 것으로 판정됐으면 위 이펙트가 곧 상태를 비운다. 그 한 프레임 동안 종단
    // 패널을 스치듯 보여 주지 않도록 여기서도 같은 판단을 본다.
    if (!token || !tokenSource || memberTokenIsDead) {
        return (
            <RoomJoinGate
                roomId={roomId}
                inviteCode={inviteCode}
                expectedGameType="OMOK"
                onReady={(readyToken, source) => {
                    setToken(readyToken)
                    setTokenSource(source)
                }}
            />
        )
    }

    // 참가가 거절되면 소켓은 다시 붙지 않는다(종단 상태). 판을 그대로 두면 아무것도 반응하지
    // 않는 죽은 화면이 되므로 이유와 빠져나갈 길을 대신 보여 준다.
    //
    // 여기까지 오는 것은 <b>토큰 탓이 아닌</b> 거절뿐이다: 방이 가득 찼다, 초대 코드가
    // 틀렸다, 이미 시작한 게임이다. 그런 사람에게는 세션이 멀쩡하므로 종단 패널이 맞는 답이다.
    // 토큰이 죽어서 거절당한 경우는 위 이펙트가 토큰을 버리고 게이트로 돌려보내므로 이 분기에
    // 닿지 않는다(C1).
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
                gameType="OMOK"
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
