import { gameAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import type { GameType, RoomSummary } from "@/lib/types"

/**
 * 초대 링크(/game/<path>/<roomId>?invite=<code>)를 연 방문자를 게임 화면에 들여보내기까지의
 * 판단 — 방 정보 조회, 회원/게스트 갈림길, 닉네임 검사, 게스트 토큰 발급 — 을 컴포넌트 밖으로
 * 뺀 로직. game-hub.ts 와 같은 이유로 React 에 의존하지 않는다: gameAPI 만 모킹하면
 * 테스트할 수 있고, room-join-gate.tsx 는 여기서 나온 결과를 그리기만 한다.
 */

/**
 * 서버(app-webflux)의 com.woobeee.game.identity.NicknameValidator 와 같은 규칙이다.
 * trim 후 1~20자, ISO 제어문자 금지. 여기서 막는 것은 왕복 한 번을 아끼기 위한 것일 뿐,
 * 판정의 최종 권한은 서버에 있다 — 규칙이 바뀌면 NicknameValidator 를 먼저 고친다.
 *
 * 미세한 차이 하나: 자바 String.trim() 은 U+0020 이하만 잘라내고 JS trim() 은 유니코드
 * 공백 전체를 잘라내므로, 전각 공백(U+3000)만으로 이뤄진 닉네임을 클라이언트가 더 엄격하게
 * 거절한다. 서버보다 좁은 쪽이므로 통과시킨 값이 서버에서 뒤집히는 일은 없다.
 */
export const NICKNAME_MAX_LENGTH = 20

// 자바 Character.isISOControl(char) 과 같은 범위: U+0000..U+001F, U+007F..U+009F
const ISO_CONTROL = /[\u0000-\u001F\u007F-\u009F]/

export type NicknameCheck =
    | { ok: true; value: string }
    | { ok: false; message: string }

export function checkNickname(raw: string): NicknameCheck {
    const trimmed = raw.trim()
    if (trimmed.length === 0 || trimmed.length > NICKNAME_MAX_LENGTH) {
        return { ok: false, message: `닉네임은 공백을 제외하고 1~${NICKNAME_MAX_LENGTH}자여야 합니다.` }
    }
    if (ISO_CONTROL.test(trimmed)) {
        return { ok: false, message: "닉네임에 사용할 수 없는 문자가 들어 있습니다." }
    }
    return { ok: true, value: trimmed }
}

export type RoomSummaryOutcome =
    | { kind: "summary"; summary: RoomSummary }
    | { kind: "error"; message: string }

/**
 * 초대 코드가 비어 있으면 서버를 부르지 않는다 — invite 쿼리 파라미터가 없는 링크는
 * 붙여넣다 잘린 것이고, 서버는 그 구분을 해줄 수 없다.
 */
export async function loadRoomSummary(
    roomId: string,
    inviteCode: string
): Promise<RoomSummaryOutcome> {
    if (!inviteCode) {
        return { kind: "error", message: "초대 코드가 없는 링크입니다." }
    }

    try {
        return { kind: "summary", summary: await gameAPI.getRoomSummary(roomId, inviteCode) }
    } catch (error) {
        console.error("Failed to load room summary:", error)
        return { kind: "error", message: describeGameApiError(error, "방을 찾을 수 없습니다.") }
    }
}

export type GuestJoinOutcome =
    | { kind: "token"; token: string }
    | { kind: "error"; message: string }

export async function joinRoomAsGuest(
    roomId: string,
    inviteCode: string,
    rawNickname: string
): Promise<GuestJoinOutcome> {
    const nickname = checkNickname(rawNickname)
    if (!nickname.ok) {
        return { kind: "error", message: nickname.message }
    }

    try {
        const guest = await gameAPI.issueGuestToken(roomId, inviteCode, nickname.value)
        return { kind: "token", token: guest.token }
    } catch (error) {
        console.error("Failed to issue guest token:", error)
        return { kind: "error", message: describeGameApiError(error, "참가하지 못했습니다.") }
    }
}

/**
 * 게이트가 지금 무엇을 그려야 하는지. 컴포넌트는 이 결과를 switch 로 받아 그리기만 한다.
 * member-ready 는 "회원 토큰이 이미 있으니 게임 화면으로 넘겨라" 는 뜻이다.
 */
export type JoinGateStage =
    | { kind: "loading" }
    | { kind: "error"; message: string }
    | { kind: "wrong-game" }
    | { kind: "member-ready"; token: string }
    | { kind: "needs-identity"; summary: RoomSummary }

export interface JoinGateInput {
    authLoading: boolean
    isAuthenticated: boolean
    /** tokenManager.getToken() — SSR 에서는 null 이다. */
    memberToken: string | null
    summary: RoomSummary | null
    expectedGameType: GameType
    error: string | null
}

export function decideJoinGate(input: JoinGateInput): JoinGateStage {
    if (input.error) {
        return { kind: "error", message: input.error }
    }
    if (input.authLoading || !input.summary) {
        return { kind: "loading" }
    }
    if (input.summary.gameType !== input.expectedGameType) {
        return { kind: "wrong-game" }
    }
    // 로그인 상태면 회원 access token 을 그대로 쓴다. isAuthenticated 가 참인데 토큰이 없는
    // 어긋난 상태(수동 삭제 등)에서는 갈림길을 보여줘 다시 로그인하거나 게스트로 들어가게 한다.
    if (input.isAuthenticated && input.memberToken) {
        return { kind: "member-ready", token: input.memberToken }
    }
    return { kind: "needs-identity", summary: input.summary }
}

export const GAME_TYPE_LABELS: Record<GameType, string> = {
    OMOK: "오목",
    DODGE: "장애물피하기",
}

/** "2 / 8명 · 이미 시작된 게임입니다" */
export function describeRoomOccupancy(summary: RoomSummary): string {
    const occupancy = `${summary.participantCount} / ${summary.capacity}명`
    return summary.status === "WAITING" ? occupancy : `${occupancy} · 이미 시작된 게임입니다`
}
