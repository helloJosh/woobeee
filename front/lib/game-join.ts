import { gameAPI, tokenManager } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import type { SocketStatus } from "@/lib/game-socket"
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

    // participantId 가 없거나 이상하면 없는 것으로 친다 — 항목 전체를 버리지 않는다.
    // 버리면 그 게스트는 게이트에서 새 닉네임으로 다시 들어가 방에 두 자리를 차지한다
    // (원래 자리는 이탈 유예 30초 동안 그대로 잡혀 있다). 쓸 수 있는 토큰을 못 쓰게 되는
    // 쪽이 화면에서 나를 못 찾는 쪽보다 훨씬 나쁘다.
    const participantId = typeof entry.participantId === "string" && entry.participantId.length > 0
        ? entry.participantId
        : null
    return { token: entry.token, participantId }
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

/**
 * 소켓이 참가를 거절당했을 때 이 방의 게스트 토큰을 버린다. 돌려주는 값은 실제로 버렸는지다.
 *
 * <p>게스트 토큰은 6시간 TTL 로 sessionStorage 에 남아 새로고침 복구에 쓰이는데, 그보다 먼저
 * 죽는 경우(방이 정리됐다, 서버가 재시작해 Redis 항목이 사라졌다)를 알 수 있는 지점은 소켓
 * JOIN 이 거절되는 순간뿐이다 — 게이트는 이미 언마운트된 뒤다. 지우지 않으면 새로고침할
 * 때마다 게이트가 죽은 토큰을 다시 꺼내 소켓에 물리고, 게스트는 닉네임 폼으로 돌아갈 길이
 * 영영 없다.
 *
 * <p>두 게임 화면이 같은 이펙트를 쓰는데, 그 안에 조건을 두면 테스트가 닿지 않는다("closed
 * 에서도 지운다" 로 바꿔도 아무 테스트가 깨지지 않는다 — 그러면 잠깐 끊긴 게스트가 자리를
 * 잃는다). 조건을 여기로 내려 고정한다. `rejected` 만 종단이면서 <b>서버가 우리를 거부한</b>
 * 상태다. `closed` 는 우리가 닫았거나 재시도를 다 쓴 것이라 토큰은 여전히 멀쩡할 수 있다.
 */
export function discardGuestTokenOnRejection(roomId: string, status: SocketStatus): boolean {
    if (status !== "rejected") {
        return false
    }
    clearStoredGuestToken(roomId)
    return true
}

/**
 * 소켓이 참가를 거절당했을 때, 그 이유가 "지금 들고 있는 <b>회원</b> 토큰이 죽었다" 인지.
 *
 * <p>이것이 없을 때가 C1 이었다. `hooks/use-auth.tsx` 의 `isAuthenticated` 는
 * `!!tokenManager.getToken()` — 문자열이 있느냐일 뿐 만료를 보지 않는다. 그래서 죽은 액세스
 * 토큰이 localStorage 에 남은 방문자가 초대 링크를 열면 게이트가 곧바로 `ready/member` 를
 * 내주고 <b>닉네임 폼은 아예 뜨지 않는다</b>. HTTP 로는 아무도 눈치채지 못한다 —
 * `RoomController.summary` 는 인증이 필요 없고 `GameAuthWebFilter` 는 잘못된 토큰을 거부하지
 * 않고 무시한다. 처음으로 알아채는 것이 소켓 JOIN 이고, 거기서 종단 `rejected` 가 되면
 * 화면에는 "처음부터 다시 참가해 주세요" 와 새로고침 버튼이 뜬다 — 새로고침하면 게이트가
 * 같은 죽은 토큰을 다시 넘겨 <b>똑같은 거절</b>이 재현된다. 게스트 갈림길로 가는 길이 없다.
 *
 * <p>게스트 쪽은 이미 옳게 하고 있다({@link discardGuestTokenOnRejection}). 회원 쪽도 같이
 * 한다 — 다만 <b>모든 거절을 토큰 삭제로 바꾸면 안 된다</b>. 멀쩡히 로그인한 회원이 방이
 * 가득 차서(`game_roomFull`), 초대 코드가 틀려서(`game_invalidInviteCode`), 이미 시작한
 * 게임이라서(`game_gameAlreadyStarted`) 거절당하는 것은 토큰의 문제가 아니다. 그 사람의
 * 세션까지 날리면 게임 목록으로 돌아갔을 때 로그아웃돼 있다.
 *
 * <p>그래서 신원 판정이 실패한 코드 셋만 본다. 서버(GameErrorCode)의 "신원" 묶음 중 방문자가
 * 들고 온 토큰을 무효로 만드는 것들이다.
 */
export const DEAD_MEMBER_TOKEN_CODES: readonly string[] = [
    // JoinAuthenticator 가 토큰을 못 읽었다 — 만료됐거나 다른 방의 것이다.
    "game_invalidGameToken",
    // 토큰이 아예 없거나 인증이 서지 않았다.
    "game_unauthorized",
    // 토큰은 읽혔는데 그 회원 행이 없다. 다시 로그인하는 것 말고 할 수 있는 일이 없다.
    "game_memberNotFound",
]

export interface MemberTokenDiscardInput {
    /** 지금 소켓에 물려 있는 토큰의 출처. 아직 게이트를 지나지 않았으면 null. */
    source: JoinTokenSource | null
    status: SocketStatus
    /** 거절 직전에 서버가 보내 준 ERROR 프레임의 code. 프레임이 오기 전에 끊기면 undefined. */
    errorCode: string | undefined
}

export function shouldDiscardMemberToken(input: MemberTokenDiscardInput): boolean {
    if (input.source !== "member" || input.status !== "rejected") {
        return false
    }
    return input.errorCode !== undefined && DEAD_MEMBER_TOKEN_CODES.includes(input.errorCode)
}

/**
 * 위 판정에 따라 실제로 버린다. 돌려주는 값은 버렸는지다.
 * {@link discardGuestTokenOnRejection} 과 같은 자리, 같은 이유로 여기 있다 — 저장소를
 * 건드리는 일이 컴포넌트 안에 있으면 테스트가 닿지 않는다.
 *
 * <p><b>액세스 토큰만 버린다.</b> {@code tokenManager.removeToken()} 은 리프레시 토큰과
 * memberId·role 까지 함께 지우는데, 그러면 만료된 액세스 토큰 하나 때문에 blog·auth 세션까지
 * 조용히 로그아웃된다 — 정작 그 만료는 리프레시 토큰이 고칠 수 있었던 것이다. 특히 나쁜
 * 순서가 실제로 있다: `useVerifiedMemberId` 의 `gameAPI.me()` 가 401 을 받아 <b>갱신에
 * 성공해</b> 새 토큰을 저장한 직후, 낡은 토큰으로 보낸 JOIN 의 거절이 뒤늦게 도착한다.
 * 전부 지우면 방금 받아 온 멀쩡한 토큰까지 날아간다.
 *
 * <p>액세스 토큰만 지워도 게이트로 돌아가는 것은 그대로다 — `decideJoinGate` 는
 * `memberToken` 이 null 이면 `needs-identity` 를 내주므로 닉네임 폼에 닿는다. 그리고 다음
 * 인증 요청이 401 을 받는 순간 `refreshAccessToken` 이 세션을 조용히 되살린다.
 */
export function discardMemberTokenOnRejection(input: MemberTokenDiscardInput): boolean {
    if (!shouldDiscardMemberToken(input)) {
        return false
    }
    tokenManager.removeAccessToken()
    return true
}

/**
 * JOIN 마다 다시 읽는 토큰. {@code createGameSocket} 의 `token` 에 <b>함수로</b> 넘긴다.
 *
 * <p>게이트가 넘긴 문자열을 그대로 고정하면(I7), 액세스 토큰의 TTL 이 판 도중에 지나는 순간
 * 이후의 모든 재접속 JOIN 이 인증에서 막히고 그 실패는 곧바로 종단 `rejected` 가 된다 —
 * 30초 유예 안에 돌아올 수 있었던 사람이 자리를 잃는다.
 *
 * <p><b>출처를 봐야 한다.</b> 게스트는 sessionStorage 의 게스트 토큰을 계속 써야 하고
 * `tokenManager.getToken()` 으로 새어 나가면 안 된다 — 같은 브라우저에 회원 토큰이 있는
 * 사람이 게스트로 들어와 두고 있는 경우, 회원 토큰으로 재접속하면 서버가 보는 신원이 바뀌어
 * 같은 사람이 방에서 두 자리를 차지한다.
 *
 * @param gateToken 게이트가 넘긴 토큰. 폴백으로만 쓴다.
 */
export function resolveJoinToken(
    roomId: string,
    source: JoinTokenSource,
    gateToken: string
): string {
    if (source === "guest-session") {
        // 저장이 막힌 브라우저(사파리 프라이빗 모드 등)에서는 storeGuestToken 이 조용히
        // 실패한다. 그때 빈 문자열을 보내면 멀쩡한 토큰을 들고도 재접속이 전부 막히므로,
        // 게이트가 넘긴 토큰으로 되돌아간다.
        return readStoredGuestToken(roomId) ?? gateToken
    }
    // 회원은 폴백하지 않는다. 게이트가 `member` 를 내줬다는 것은 그 시점에
    // tokenManager 에 토큰이 있었다는 뜻이므로, 지금 없다면 누군가 의도적으로 지운
    // 것이다 — 로그아웃했거나, 바로 위 shouldDiscardMemberToken 이 죽은 토큰을 버렸거나.
    // 그 자리에 낡은 문자열을 되살리면 C1 을 그대로 되돌리는 셈이다.
    return tokenManager.getToken() ?? ""
}

function isStoredGuestToken(value: unknown): value is StoredGuestToken {
    if (typeof value !== "object" || value === null) {
        return false
    }
    const candidate = value as Partial<StoredGuestToken>
    // participantId 는 검사하지 않는다. 항목을 쓸 수 있게 하는 것은 토큰과 발급 시각뿐이고,
    // participantId 의 유효성은 readStoredGuestIdentity 가 항목을 버리지 않고 걸러 낸다.
    return typeof candidate.token === "string"
        && candidate.token.length > 0
        && typeof candidate.issuedAt === "number"
        && Number.isFinite(candidate.issuedAt)
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
/**
 * 게이트가 넘긴 토큰이 <b>무엇인지</b>. 게임 화면은 이것 없이는 거절을 옳게 처리할 수 없다 —
 * 죽은 회원 토큰은 버려야 하고 게스트 토큰은 sessionStorage 에서 다시 읽어야 하는데, 토큰
 * 문자열만으로는 둘을 구분할 수 없다(둘 다 그냥 불투명한 문자열이다).
 */
export type JoinTokenSource = "member" | "guest-session"

export type JoinGateStage =
    | { kind: "loading" }
    | { kind: "error"; message: string }
    | { kind: "wrong-game" }
    | { kind: "ready"; token: string; source: JoinTokenSource }
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
