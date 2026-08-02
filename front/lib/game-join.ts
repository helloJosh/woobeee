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

/**
 * 발급받은 게스트 토큰을 탭 안에 남긴다. 새로고침하면 게이트로 되돌아오는데, 같은 닉네임을
 * 다시 넣으면 방에 남아 있는 RoomMember 때문에 중복으로 거절되고(끊긴 뒤 30초 유예 동안
 * displayName 이 그대로 잡혀 있다), 다른 닉네임을 넣으면 한 사람이 두 자리를 차지한다.
 * 토큰을 재사용하면 같은 participantId 로 Room.admit 이 RECONNECTED 를 돌려준다.
 *
 * sessionStorage 를 쓰는 이유: 탭 단위라 같은 브라우저의 다른 탭이 같은 자리를 집어가지
 * 않고, 탭을 닫으면 사라진다. localStorage 였다면 두 탭이 한 자리를 두고 싸운다.
 *
 * 만료는 서버(GuestIdentityService.GUEST_TOKEN_TTL, 6시간)와 맞춰 스스로 지운다. 그보다
 * 먼저 토큰이 죽는 경우(방 정리 등)는 여기서 알 수 없다 — 소켓 JOIN 이 거절당한 화면이
 * clearStoredGuestToken 을 불러야 한다.
 */
const GUEST_TOKEN_KEY_PREFIX = "woobeee:game:guest-token:"
const GUEST_TOKEN_TTL_MS = 6 * 60 * 60 * 1000

interface StoredGuestToken {
    token: string
    issuedAt: number
    /**
     * 발급 응답의 participantId(`g:<uuid>`). 게임 화면이 명단에서 "나" 를 찾는 유일한
     * 근거다 — 서버는 소켓으로 그것을 따로 알려주지 않고 토큰은 불투명한 난수라 되짚을 수
     * 없다(GuestIdentityService 가 Redis 해시에만 담아 둔다). 선택 필드로 둔 이유는 이
     * 필드가 생기기 전에 저장된 항목을 만나도 토큰만은 계속 쓰게 하기 위해서다 — 여기서
     * 항목을 버리면 그 게스트는 새 닉네임으로 다시 들어가 한 사람이 두 자리를 차지한다.
     */
    participantId?: string
}

/** 저장해 둔 게스트 신원. participantId 는 예전 형식으로 저장된 항목에서는 null 이다. */
export interface StoredGuestIdentity {
    token: string
    participantId: string | null
}

function guestTokenKey(roomId: string): string {
    return `${GUEST_TOKEN_KEY_PREFIX}${roomId}`
}

export function storeGuestToken(roomId: string, token: string, participantId: string): void {
    if (typeof window === "undefined") {
        return
    }
    const entry: StoredGuestToken = { token, issuedAt: Date.now(), participantId }
    try {
        window.sessionStorage.setItem(guestTokenKey(roomId), JSON.stringify(entry))
    } catch {
        // 사파리 프라이빗 모드 등에서 저장이 막힐 수 있다. 저장 실패가 참가 자체를 막을
        // 이유는 없으므로 새로고침 복구만 포기한다.
    }
}

export function readStoredGuestIdentity(roomId: string): StoredGuestIdentity | null {
    if (typeof window === "undefined") {
        return null
    }
    let raw: string | null = null
    try {
        raw = window.sessionStorage.getItem(guestTokenKey(roomId))
    } catch {
        return null
    }
    if (!raw) {
        return null
    }

    let entry: unknown
    try {
        entry = JSON.parse(raw)
    } catch {
        clearStoredGuestToken(roomId)
        return null
    }

    if (!isStoredGuestToken(entry) || Date.now() - entry.issuedAt >= GUEST_TOKEN_TTL_MS) {
        clearStoredGuestToken(roomId)
        return null
    }
    return { token: entry.token, participantId: entry.participantId ?? null }
}

export function readStoredGuestToken(roomId: string): string | null {
    return readStoredGuestIdentity(roomId)?.token ?? null
}

export function clearStoredGuestToken(roomId: string): void {
    if (typeof window === "undefined") {
        return
    }
    try {
        window.sessionStorage.removeItem(guestTokenKey(roomId))
    } catch {
        // 위와 같다.
    }
}

function isStoredGuestToken(value: unknown): value is StoredGuestToken {
    if (typeof value !== "object" || value === null) {
        return false
    }
    const candidate = value as Partial<StoredGuestToken>
    return typeof candidate.token === "string"
        && candidate.token.length > 0
        && typeof candidate.issuedAt === "number"
        && Number.isFinite(candidate.issuedAt)
        // 없어도 되지만, 있다면 비지 않은 문자열이어야 한다 — 빈 문자열을 그대로 통과시키면
        // 게임 화면이 명단의 누구와도 맞지 않는 식별자를 나로 믿는다.
        && (candidate.participantId === undefined
            || (typeof candidate.participantId === "string" && candidate.participantId.length > 0))
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
        storeGuestToken(roomId, guest.token, guest.participantId)
        return { kind: "token", token: guest.token }
    } catch (error) {
        console.error("Failed to issue guest token:", error)
        return { kind: "error", message: describeGameApiError(error, "참가하지 못했습니다.") }
    }
}

/**
 * 새 참가자를 막아야 하는 이유. 서버도 같은 것을 막는다 — GuestIdentityService.issue 가 방
 * 존재·초대 코드·닉네임에 더해 방 상태와 정원까지 보고, 들어갈 수 없는 방이면 토큰을 아예
 * 만들지 않는다(`game_gameAlreadyStarted`, `game_roomFull`). 여기서 한 번 더 보는 것은 화면
 * 때문이다: 발급을 시도해 실패로 알게 되는 것보다, 방 요약을 읽은 시점에 미리 안내하는 쪽이
 * 낫다(닉네임을 입력하기 전에 알 수 있다).
 *
 * <p>둘 사이에는 여전히 경합이 있다 — 요약을 읽고 발급을 요청하는 사이에 마지막 자리가 찰 수
 * 있다. 그때는 발급이 `game_roomFull` 로 거절되고 그 문구가 배너에 뜬다. 최종 판정은 서버다.
 */
export type RoomJoinBlock = { reason: "started" | "full"; message: string }

export function describeJoinBlock(summary: RoomSummary): RoomJoinBlock | null {
    if (summary.status !== "WAITING") {
        return {
            reason: "started",
            message: "이미 시작된 게임이라 새로 참가할 수 없습니다. 방장에게 새 방을 만들어 달라고 하세요.",
        }
    }
    if (summary.participantCount >= summary.capacity) {
        return {
            reason: "full",
            message: `정원이 찼습니다 (${summary.participantCount} / ${summary.capacity}명). 자리가 나면 다시 시도해 주세요.`,
        }
    }
    return null
}

/**
 * 게이트가 지금 무엇을 그려야 하는지. 컴포넌트는 이 결과를 switch 로 받아 그리기만 한다.
 * ready 는 "쓸 수 있는 토큰이 있으니 게임 화면으로 넘겨라" 는 뜻이다.
 */
export type JoinGateStage =
    | { kind: "loading" }
    | { kind: "error"; message: string }
    | { kind: "wrong-game" }
    | { kind: "ready"; token: string; source: "member" | "guest-session" }
    | { kind: "needs-identity"; summary: RoomSummary; block: RoomJoinBlock | null }

export interface JoinGateInput {
    /** 지금 보고 있는 방. */
    roomId: string
    /**
     * summary/error/storedGuestToken 이 설명하는 방. roomId 와 다르면 그 셋은 아직 이전
     * 방의 것이다 — 프롭이 바뀐 렌더와 그것을 반영하는 이펙트 사이에 한 프레임이 있다.
     * 이 프레임을 걸러내지 않으면 A 방의 게스트 토큰을 B 방으로 넘기게 된다(서버의
     * JoinAuthenticator 가 401 로 막지만, 이유 없는 소켓 거절로 보일 뿐이다).
     */
    loadedRoom: string | null
    authLoading: boolean
    isAuthenticated: boolean
    /** tokenManager.getToken() — SSR 에서는 null 이다. 방과 무관하다. */
    memberToken: string | null
    /** readStoredGuestToken(roomId) — 같은 탭에서 이 방의 게스트 토큰을 이미 받았다면 그것. */
    storedGuestToken: string | null
    summary: RoomSummary | null
    expectedGameType: GameType
    error: string | null
}

export function decideJoinGate(input: JoinGateInput): JoinGateStage {
    if (input.loadedRoom !== input.roomId) {
        return { kind: "loading" }
    }
    if (input.error) {
        return { kind: "error", message: input.error }
    }
    if (input.authLoading || !input.summary) {
        return { kind: "loading" }
    }
    if (input.summary.gameType !== input.expectedGameType) {
        return { kind: "wrong-game" }
    }

    // 이 방의 게스트 토큰이 이미 있으면 회원 토큰보다 먼저 쓴다. 그 토큰에 묶인 participantId
    // 로 들어가야 Room.admit 이 RECONNECTED 를 돌려주고 원래 자리로 복귀한다 — 회원 토큰으로
    // 바꿔 들어가면 같은 사람이 두 자리를 차지한다.
    if (input.storedGuestToken) {
        return { kind: "ready", token: input.storedGuestToken, source: "guest-session" }
    }

    // 로그인 상태면 회원 access token 을 그대로 쓴다. isAuthenticated 가 참인데 토큰이 없는
    // 어긋난 상태(수동 삭제 등)에서는 갈림길을 보여줘 다시 로그인하거나 게스트로 들어가게 한다.
    //
    // 정원·상태로 막지 않는 것은 의도적이다. 새로고침한 기존 회원 참가자와 처음 온 회원을
    // RoomSummary(인원 수만 있고 명단이 없다)로는 구분할 수 없는데, 막으면 재접속이 깨진다.
    // Room.admit 은 이미 멤버인 경우 정원·상태 검사보다 먼저 RECONNECTED 를 돌려주므로,
    // 판단을 서버로 넘기는 쪽이 옳다. 아래 게스트 갈림길은 반대로 확실히 새 참가자다.
    if (input.isAuthenticated && input.memberToken) {
        return { kind: "ready", token: input.memberToken, source: "member" }
    }

    return {
        kind: "needs-identity",
        summary: input.summary,
        block: describeJoinBlock(input.summary),
    }
}

export const GAME_TYPE_LABELS: Record<GameType, string> = {
    OMOK: "오목",
    DODGE: "장애물피하기",
}

/** `/game/<segment>/<roomId>` 의 가운데 조각. app/game/page.tsx 의 GAMES 가 이것을 쓴다. */
export const GAME_ROUTE_SEGMENTS: Record<GameType, string> = {
    OMOK: "omok",
    DODGE: "dodge",
}

/**
 * 초대 링크의 정규 형태. game-hub.ts 의 createGameRoom 이 방을 만든 직후 만드는 URL 과 같은
 * 모양이어야 한다 — 게이트는 이 경로를 `/login?next=` 에 실어 보내고, 로그인을 마친 방문자는
 * 정확히 이 URL 로 되돌아온다. 모양이 어긋나면 로그인 뒤 404 로 떨어진다.
 */
export function roomPath(gameType: GameType, roomId: string, inviteCode: string): string {
    const base = `/game/${GAME_ROUTE_SEGMENTS[gameType]}/${encodeURIComponent(roomId)}`
    return inviteCode ? `${base}?invite=${encodeURIComponent(inviteCode)}` : base
}

/**
 * "2 / 8명". 시작 여부는 붙이지 않는다 — status 가 WAITING 이 아니면 describeJoinBlock 이
 * 항상 같은 사실을 결과("새로 참가할 수 없습니다")까지 담아 말하므로, 여기서 또 쓰면 같은
 * 문장이 화면에 두 번 나온다.
 */
export function describeRoomOccupancy(summary: RoomSummary): string {
    return `${summary.participantCount} / ${summary.capacity}명`
}
