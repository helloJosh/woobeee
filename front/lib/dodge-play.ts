import type { DirectionName } from "@/lib/dodge-engine"
import { getFriendlyErrorMessage } from "@/lib/errors/error-utils"
import {
    isServerMessage,
    type DodgeCell,
    type DodgePosition,
    type GameEndPayload,
    type ServerMessage,
    type SocketStatus,
} from "@/lib/game-socket"
import type { ParticipantView, RoomStatus } from "@/lib/types"

/**
 * 장애물피하기 플레이 화면의 판단 전부. omok-play.ts 와 같은 자리에 같은 이유로 있다 —
 * React 에 의존하지 않는 순수 함수와 리듀서로 두고 테스트로 고정한다.
 * app/game/dodge/[roomId]/page.tsx 는 이 모듈의 결과를 그리기만 한다.
 *
 * <p>서버 계약의 단일 출처는 app-webflux 의 `DodgeGameSink` / `DodgeGame` /
 * `RoomCommandDispatcher` 다. 아래 주석의 규칙들은 그 코드에서 옮겨 왔다.
 *
 * <p><b>lib/dodge-engine.ts 를 여기서 돌리지 않는다.</b> 그 포트는 <i>기보 재생</i>용이다.
 * 실시간 판의 권위는 전적으로 서버에 있고(장애물 생성은 서버 시드의 난수열이다), 같은 엔진을
 * 화면에서 함께 돌리면 서버 프레임과 예측 프레임 두 개의 진실이 생겨 반드시 갈라진다.
 * 화면은 서버가 보내 준 프레임만 그린다.
 */

/**
 * 색을 배정할 수 있는 최대 인원. 방 정원(DODGE 8인)과 같다 — 그보다 많아지면 색이 돌아
 * 겹치지만, 말 위에 번호를 함께 그리므로 구분 자체가 불가능해지지는 않는다.
 */
export const DODGE_PLAYER_COLOR_COUNT = 8

export interface DodgeRoomState {
    participants: ParticipantView[]
    hostParticipantId: string
    status: RoomStatus
    /** 서버가 보내 준 마지막 프레임의 틱 번호. */
    tick: number
    /** 살아 있는 참가자의 좌표만 들어 있다 — 탈락자는 서버의 positions 에서 지워진다. */
    positions: DodgePosition[]
    obstacles: DodgeCell[]
    /**
     * 색·번호 배정 순서. <b>추가만 한다.</b> 명단(participants) 순서를 그대로 쓰면 누가
     * 도중에 나갈 때 그 뒤 사람들의 색이 한 칸씩 밀려, 판이 도는 도중에 내 말 색이 바뀐다.
     */
    colorOrder: string[]
    /**
     * 프레임(DODGE_TICK/GAME_SNAPSHOT)을 한 번이라도 받았는가. "positions 에 내가 없다"
     * 만으로 탈락을 판정하면, 시작 직후 첫 틱이 오기 전(positions 가 빈 배열)에 모두가
     * 탈락한 것처럼 보인다.
     */
    frameSeen: boolean
    /** GAME_END 페이로드 그대로. 문구는 selfParticipantId 를 아는 쪽에서 만든다. */
    outcome: GameEndPayload | null
    /** 서버 오류 같은 일회성 안내. */
    notice: string | null
}

export const initialDodgeRoomState: DodgeRoomState = {
    participants: [],
    hostParticipantId: "",
    status: "WAITING",
    tick: 0,
    positions: [],
    obstacles: [],
    colorOrder: [],
    frameSeen: false,
    outcome: null,
    notice: null,
}

export type DodgeRoomAction =
    | { type: "message"; message: ServerMessage }
    /** 방이 바뀌었거나 소켓을 새로 연다. 이전 방의 프레임을 남기지 않는다. */
    | { type: "reset" }

export function reduceDodgeRoom(state: DodgeRoomState, action: DodgeRoomAction): DodgeRoomState {
    switch (action.type) {
        case "reset":
            return initialDodgeRoomState
        case "message":
            return applyServerMessage(state, action.message)
    }
}

/**
 * payload 는 unknown 이다. isServerMessage 로 좁히는 것이 그것을 읽는 유일한 방법이다 —
 * `switch (message.type)` 로는 좁혀지지 않아 GAME_SNAPSHOT 의 오목 변종에서 존재하지도 않는
 * `payload.positions` 를 읽는 코드가 그대로 통과한다. 되돌리지 말 것.
 */
function applyServerMessage(state: DodgeRoomState, message: ServerMessage): DodgeRoomState {
    if (isServerMessage(message, "ROOM_STATE")) {
        const participants = message.payload.participants
        return {
            ...state,
            participants,
            hostParticipantId: message.payload.hostParticipantId,
            colorOrder: appendColorOrder(state.colorOrder, participants.map((p) => p.participantId)),
            // 서버는 게임이 끝나도 방 상태를 IN_PROGRESS 로 둔다(CLAUDE.md 의 known-gap G3).
            // 그대로 받아들이면 GAME_END 로 FINISHED 가 된 화면이 다음 ROOM_STATE(누군가
            // 접속을 끊는 것만으로도 온다) 한 번에 "진행 중" 으로 되돌아가고, 그 순간
            // canMoveInDodge 가 다시 참이 되어 끝난 판에 이동 명령을 쏘기 시작한다.
            status: state.status === "FINISHED" ? "FINISHED" : message.payload.status,
        }
    }

    if (isServerMessage(message, "GAME_START")) {
        return {
            ...state,
            status: "IN_PROGRESS",
            // 첫 프레임은 100ms 뒤 첫 DODGE_TICK 이 싣고 온다. 그전까지는 빈 판이다 —
            // 시작 좌표를 여기서 추측해 그리면(startingCells) 서버가 참가자 순서를 다르게
            // 잡았을 때 한 틱 동안 남의 자리에 내 말이 그려진다.
            tick: 0,
            positions: [],
            obstacles: [],
            frameSeen: false,
            colorOrder: appendColorOrder(
                state.colorOrder,
                state.participants.map((p) => p.participantId)
            ),
            outcome: null,
            notice: null,
        }
    }

    // 재접속 경로. ROOM_STATE 바로 뒤에 서버가 지금 프레임을 다시 보내 준다. 통째로 갈아
    // 끼운다 — 끊긴 동안 지나간 틱이 있으므로 이어 붙일 수 있는 것이 아니다.
    if (isServerMessage(message, "GAME_SNAPSHOT")) {
        const snapshot = message.payload
        // 방의 게임 종류는 고정이라 실제로는 항상 DODGE 이지만, 이 분기가 없으면 타입이
        // tick/positions 를 읽게 해 주지 않는다. 그게 이 유니온의 목적이다.
        if (snapshot.gameType !== "DODGE") {
            return state
        }
        return {
            ...state,
            tick: snapshot.tick,
            positions: snapshot.positions,
            obstacles: snapshot.obstacles,
            frameSeen: true,
            colorOrder: appendColorOrder(
                state.colorOrder,
                snapshot.positions.map((p) => p.participantId)
            ),
            // notice 는 지우지 않는다. 스냅샷은 누가 재접속하든 방 전체로 나가므로
            // (DodgeGameSink.onRejoin 은 세션 단위 전송이 없어 broadcast 한다) 여기서 지우면
            // 남이 새로고침하는 것만으로 내 오류 안내가 사라진다. 프레임만 갈아 끼운다.
        }
    }

    if (isServerMessage(message, "DODGE_TICK")) {
        const frame = message.payload
        return {
            ...state,
            tick: frame.tick,
            positions: frame.positions,
            obstacles: frame.obstacles,
            frameSeen: true,
            colorOrder: appendColorOrder(
                state.colorOrder,
                frame.positions.map((p) => p.participantId)
            ),
            // notice 는 건드리지 않는다. 틱은 초당 10번 오므로 여기서 지우면 어떤 안내도
            // 읽히기 전에 사라진다.
        }
    }

    if (isServerMessage(message, "GAME_END")) {
        return {
            ...state,
            status: "FINISHED",
            // positions/obstacles 는 마지막 프레임 그대로 둔다. 끝나는 순간의 판이 남아
            // 있어야 "누가 어디서 맞았는지" 를 볼 수 있다.
            outcome: message.payload,
            notice: null,
        }
    }

    if (isServerMessage(message, "ERROR")) {
        // payload.message 는 서버 로그용 영어다. 사용자에게 보여줄 문구는 payload.code
        // (`game_*`)로 error-messages.ts 에서 찾는다 — HTTP 실패와 같은 지도다.
        return { ...state, notice: getFriendlyErrorMessage(message.payload.code) }
    }

    // 모르는 타입은 버리지 않고 그냥 지나간다 — 서버가 새 메시지를 추가해도 화면은 살아 있다.
    return state
}

/**
 * 색 배정 순서에 처음 보는 participantId 만 뒤에 붙인다. 있는 항목은 절대 옮기지도 지우지도
 * 않는다 — 한 번 배정된 색은 그 방이 끝날 때까지 그 사람의 것이다.
 */
export function appendColorOrder(order: string[], participantIds: string[]): string[] {
    const missing = participantIds.filter((id) => !order.includes(id))
    return missing.length === 0 ? order : [...order, ...missing]
}

/**
 * 말 색·번호를 정하는 0-based 인덱스. 배정 순서에 없는 사람(프레임보다 명단이 늦게 온 아주
 * 짧은 순간)은 0 으로 떨어뜨린다 — 그리지 않는 것보다 낫다.
 */
export function colorIndexOf(order: string[], participantId: string): number {
    const index = order.indexOf(participantId)
    return index < 0 ? 0 : index % DODGE_PLAYER_COLOR_COUNT
}

export interface DodgeGridPlayer {
    participantId: string
    displayName: string
    x: number
    y: number
    /** 0-based. 컴포넌트의 색 팔레트 인덱스이자 말에 그리는 번호(+1)다. */
    colorIndex: number
    isSelf: boolean
}

/** 지금 판 위에 있는 말들. 탈락자는 서버 프레임에서 이미 빠져 있으므로 여기에도 없다. */
export function toGridPlayers(
    state: DodgeRoomState,
    selfParticipantId: string | null
): DodgeGridPlayer[] {
    const nameOf = new Map(state.participants.map((p) => [p.participantId, p.displayName]))
    return state.positions.map((position) => ({
        participantId: position.participantId,
        // 명단보다 프레임이 먼저 오거나 명단에서 빠진 뒤에도 좌표가 남아 있을 수 있다.
        // 그때는 식별자라도 보여 준다 — 빈 이름표보다 낫다.
        displayName: nameOf.get(position.participantId) ?? position.participantId,
        x: position.x,
        y: position.y,
        colorIndex: colorIndexOf(state.colorOrder, position.participantId),
        isSelf: position.participantId === selfParticipantId,
    }))
}

export interface DodgeRosterEntry {
    participantId: string
    displayName: string
    colorIndex: number
    isSelf: boolean
    alive: boolean
}

/**
 * 판 아래에 그리는 색-이름 대응표. 12×16 격자 위의 점만으로는 여덟 명 중 누가 누구인지 알 수
 * 없으므로, 색·번호와 이름을 잇는 표가 없으면 색을 여덟 개 쓰는 의미가 없다. 탈락 여부도 여기서
 * 보여 준다 — 탈락자는 판에서 사라질 뿐이라 명단이 없으면 "누가 남았는지" 를 셀 수 없다.
 */
export function toRoster(
    state: DodgeRoomState,
    selfParticipantId: string | null
): DodgeRosterEntry[] {
    const onBoard = new Set(state.positions.map((p) => p.participantId))
    return state.participants.map((participant) => ({
        participantId: participant.participantId,
        displayName: participant.displayName,
        colorIndex: colorIndexOf(state.colorOrder, participant.participantId),
        isSelf: participant.participantId === selfParticipantId,
        // 프레임을 아직 못 받았으면 아무도 탈락하지 않았다. frameSeen 주석 참고.
        alive: !state.frameSeen || onBoard.has(participant.participantId),
    }))
}

/**
 * 내가 탈락했는가. 서버는 "너는 탈락했다" 를 따로 보내 주지 않는다 — DODGE_TICK 의
 * `eliminated` 는 그 틱에만 실리므로 놓치면 끝이고 재접속 스냅샷에는 아예 없다. 남는 근거는
 * 하나뿐이다: 살아 있는 사람만 들어 있는 positions 에서 내가 빠졌다.
 */
export function isSelfEliminated(
    state: DodgeRoomState,
    selfParticipantId: string | null
): boolean {
    if (!selfParticipantId || !state.frameSeen) {
        return false
    }
    // 명단에 없는 후보(신원 확인이 아직 안 끝났다)를 탈락으로 단정하지 않는다.
    if (!state.participants.some((p) => p.participantId === selfParticipantId)) {
        return false
    }
    return !state.positions.some((p) => p.participantId === selfParticipantId)
}

/**
 * 이동 명령을 보내도 되는가. `joined` 를 요구하는 것이 핵심이다 — `open` 은 핸드셰이크만 끝난
 * 상태라 그때 보낸 이동은 서버가 JOIN 전 메시지로 버린다(GameWebSocketHandler.handleText).
 */
export function canMoveInDodge(
    state: DodgeRoomState,
    selfParticipantId: string | null,
    socketStatus: SocketStatus
): boolean {
    return (
        socketStatus === "joined"
        && state.status === "IN_PROGRESS"
        && selfParticipantId !== null
        && !isSelfEliminated(state, selfParticipantId)
    )
}

/**
 * 키보드 매핑. 대문자(CapsLock/Shift)도 같이 받는다 — `event.key` 는 "W" 로 온다.
 * 한글 입력 상태에서는 `event.key` 가 "ㅈ" 이라 WASD 가 듣지 않으므로 방향키가 있다.
 */
const KEY_TO_DIRECTION: Record<string, DirectionName> = {
    ArrowUp: "UP",
    ArrowDown: "DOWN",
    ArrowLeft: "LEFT",
    ArrowRight: "RIGHT",
    w: "UP",
    s: "DOWN",
    a: "LEFT",
    d: "RIGHT",
}

export function directionForKey(key: string): DirectionName | null {
    // 한 글자짜리 키만 소문자로 접는다. "ArrowUp" 을 소문자로 만들면 매핑에서 사라진다.
    const normalized = key.length === 1 ? key.toLowerCase() : key
    return KEY_TO_DIRECTION[normalized] ?? null
}

/**
 * 입력 중인 요소 위에서 눌린 키인가. 사이드바의 초대 링크 입력칸이 포커스를 가진 채 방향키를
 * 누르면 캐럿을 움직이려는 것이지 말을 움직이려는 것이 아니다 — 여기서 걸러내지 않으면
 * preventDefault 때문에 링크를 키보드로 선택할 수 없다.
 *
 * <p>DOM 타입 대신 최소한의 모양만 받는다. 이 모듈은 React 도 DOM 도 모른다.
 */
export function isTypingElement(
    target: { tagName?: string; isContentEditable?: boolean } | null | undefined
): boolean {
    if (!target) {
        return false
    }
    if (target.isContentEditable) {
        return true
    }
    const tag = target.tagName?.toUpperCase()
    return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT"
}

/** 마지막으로 <b>실제로 나간</b> 이동 명령. 보내지 못한 프레임은 여기 기록하지 않는다. */
export interface SentMove {
    tick: number
    direction: DirectionName
}

/**
 * 같은 틱에 같은 방향을 다시 보낼 필요가 있는가.
 *
 * <p>키를 누르고 있으면 브라우저 자동 반복이 초당 30번쯤 keydown 을 낸다. 서버 틱은 초당
 * 10번이고 참가자당 <b>마지막 입력 하나만</b> 남기므로(DodgeGameSink.onGameCommand 의
 * `buffer.put`), 그 셋 중 둘은 언제나 버려질 프레임이다 — 8명이 동시에 누르면 초당 240 프레임이
 * 소켓으로 나가고 그중 80개만 쓰인다. 틱·방향이 같으면 보내지 않는다.
 *
 * <p><b>중요:</b> 호출자는 `GameSocket.send` 가 seq(숫자)를 돌려줬을 때만 그 값을 기록해야
 * 한다. send 는 소켓이 열려 있지 않으면 프레임을 버리고 `null` 을 돌려주는데, 그것을 "보냈다"
 * 로 기록하면 그 틱 동안 같은 방향이 영영 막힌다 — 재접속 직후 첫 입력이 사라지는 경로다.
 */
export function shouldSendMove(last: SentMove | null, next: SentMove): boolean {
    return last === null || last.tick !== next.tick || last.direction !== next.direction
}

/**
 * 진행 중인 판의 한 줄 안내. 남은 인원과 틱을 함께 보여 주는 이유는 이 게임에 다른 진행
 * 표시가 없기 때문이다 — 장애물이 늘어나는 것 말고는 시간이 흐른다는 신호가 없다.
 */
export function describeDodgeProgress(state: DodgeRoomState, eliminated: boolean): string {
    if (state.status !== "IN_PROGRESS") {
        return ""
    }
    // GAME_START 와 첫 DODGE_TICK 사이의 100ms. positions 가 아직 비어 있으므로 세면
    // "생존 0명" 이라는 거짓말이 된다.
    if (!state.frameSeen) {
        return "게임 시작 — 첫 프레임을 기다리는 중입니다…"
    }
    const alive = `생존 ${state.positions.length}명 · ${state.tick}틱`
    return eliminated ? `탈락 — 관전 중 · ${alive}` : `방향키 또는 WASD 로 이동 · ${alive}`
}

/**
 * 종료 문구. 승자가 없으면 winnerParticipantId 는 null 이 아니라 빈 문자열이다
 * (DodgeGameSink.recordAndBroadcastEnd).
 *
 * <p>오목의 describeGameOutcome 과 달리 내 등수를 함께 말한다 — 여덟 명 중 1등이 아닌 것은
 * 곧 졌다는 뜻이 아니라 등수가 있다는 뜻이다.
 */
export function describeDodgeOutcome(
    outcome: GameEndPayload,
    selfParticipantId: string | null
): string {
    if (!outcome.winnerParticipantId) {
        return "게임 종료 — 승자가 없습니다."
    }
    if (selfParticipantId && outcome.winnerParticipantId === selfParticipantId) {
        return "게임 종료 — 1위입니다!"
    }
    const winner = outcome.ranks.find((entry) => entry.rank === 1)?.displayName ?? "-"
    const myRank = selfParticipantId
        ? outcome.ranks.find((entry) => entry.participantId === selfParticipantId)?.rank
        : undefined
    return myRank === undefined
        ? `게임 종료 — 1위 ${winner}`
        : `게임 종료 — 1위 ${winner} · 내 순위 ${myRank}위`
}

/** 종료 화면의 등수표. 서버가 준 순서를 믿지 않고 rank 로 정렬한다. */
export function sortedRanks(outcome: GameEndPayload): GameEndPayload["ranks"] {
    return [...outcome.ranks].sort((a, b) => a.rank - b.rank)
}
