import { getFriendlyErrorMessage } from "@/lib/errors/error-utils"
import type { SocketStatus } from "@/lib/game-socket"
import type { ParticipantView } from "@/lib/types"

/**
 * 두 게임 화면(오목·장애물피하기)이 함께 쓰는 방 판단. 처음에는 omok-play.ts 안에 있었지만
 * 게임과 아무 상관이 없는 것들이다 — "명단에서 나를 찾는다", "소켓 상태를 문장으로 만든다" 는
 * 방 화면이면 어디서나 같다. 두 번째 화면이 생기면서 여기로 옮겼다: 장애물피하기가
 * omok-play 를 import 하게 두면 다음 화면도 그렇게 하고, 결국 오목 모듈이 공용 모듈 행세를
 * 하게 된다.
 *
 * <p>game-join.ts / room-sidebar.ts / omok-play.ts / dodge-play.ts 와 같은 이유로 React 에
 * 의존하지 않는다.
 */

export interface SelfIdentityInput {
    /** tokenManager.getMemberId() / useAuth().memberId. 로그인하지 않았으면 null. */
    memberId: number | null
    /** 이 방의 게스트 토큰과 함께 저장해 둔 participantId. readStoredGuestIdentity 참고. */
    guestParticipantId: string | null
    /** 마지막 ROOM_STATE 의 명단. 아직 없으면 빈 배열. */
    participants: ParticipantView[]
}

/**
 * 회원의 participantId. 서버 `GameParticipant.member` 가 `"m:" + memberId` 로 만든다.
 *
 * <p>규칙을 아는 곳은 여기 하나다 — 기보 다시보기(lib/replay-view.ts)도 같은 규칙으로 내
 * 말을 찾으므로, 문자열을 여기저기서 다시 조립하면 한쪽만 고쳐진 채 남는다.
 */
export function memberParticipantId(memberId: number | null): string | null {
    return memberId === null ? null : `m:${memberId}`
}

/**
 * 내 memberId 로 무엇을 믿을 것인가.
 *
 * <p>`localStorage.authMemberId`(useAuth().memberId)는 로그인할 때 우리가 적어 둔 값이라
 * 토큰과 어긋날 수 있다. `GET /api/game/me` 는 게임 서버가 <b>소켓 JOIN 과 같은 검증기</b>로
 * 토큰에서 뽑아 준 값이라 구조적으로 정확하다 — 그래서 그쪽이 오면 그쪽을 쓴다.
 *
 * <p>다만 그 호출은 실패할 수 있고(게임 서버가 죽어 있어도 오목 화면 자체는 웹소켓으로
 * 돌아간다) 게스트에게는 애초에 부를 수 없다(`GamePrincipals.require` 가 던진다). 그때는
 * 저장된 값으로 되돌아간다 — 신원을 아예 잃는 것보다 낫다.
 */
export function chooseMemberId(verified: number | null, stored: number | null): number | null {
    return verified !== null ? verified : stored
}

/**
 * 명단에서 "나" 를 찾는다.
 *
 * <p>서버는 내 participantId 를 따로 알려주지 않지만 규칙으로 계산할 수 있다 —
 * `GameParticipant.member` 가 `"m:" + memberId`, `GameParticipant.guest` 가
 * `"g:" + guestId` 를 만든다. 회원은 memberId 로 그대로 만들 수 있고, 게스트는 토큰을
 * 발급받을 때 서버가 돌려준 participantId 를 토큰 옆에 저장해 둔다.
 *
 * <p>"명단의 마지막 사람이 나" 같은 추측은 쓰지 않는다. 방장이 새로고침하면 명단은
 * [방장, 손님] 이라 마지막은 내가 아니고, 그 순간 화면 전체가 상대의 것이 된다 — 판이 내
 * 차례에 잠기고 상대 차례에 열리며, 방장인데 시작 버튼이 사라진다. 장애물피하기에서는 더
 * 나쁘다: 8명 중 남의 말이 내 말로 표시되고, 내가 탈락해도 화면은 계속 조작을 받는다.
 *
 * <p>게스트 토큰이 회원 토큰보다 우선인 것은 decideJoinGate 의 토큰 우선순위와 같다.
 * 실제로 JOIN 에 실려 간 토큰이 게스트 토큰이므로 서버가 보는 나도 그쪽이다.
 */
export function resolveSelfParticipantId(input: SelfIdentityInput): string | null {
    const candidate = input.guestParticipantId ?? memberParticipantId(input.memberId)

    if (candidate && input.participants.some((p) => p.participantId === candidate)) {
        return candidate
    }
    if (input.participants.length === 0) {
        return candidate
    }
    // 명단에 후보가 없다. ROOM_STATE 는 내 참가가 확정된 뒤에만 오므로 나는 반드시 명단에
    // 있다 — 방에 한 사람뿐이면 그게 나다. (localStorage 의 authMemberId 가 낡아 후보가
    // 어긋난 경우를 여기서 건져낸다.)
    if (input.participants.length === 1) {
        return input.participants[0].participantId
    }
    return candidate
}

/**
 * 더 이상 아무것도 기다릴 것이 없는 상태. createGameSocket 의 `settled` 와 같은 둘이다 —
 * 이 상태에서는 소켓이 다시 붙지 않으므로 화면도 진행 중임을 뜻하는 표시(스피너)를 걷어야
 * 한다. 돌아가는 스피너는 "곧 될 것" 이라는 약속인데 여기서는 지켜지지 않는다.
 */
export function isSocketSettled(status: SocketStatus): boolean {
    return status === "rejected" || status === "closed"
}

/**
 * 연결 상태 안내. `joined` 일 때만 null 이고, 나머지는 전부 지금 화면이 신뢰할 수 없는
 * 상태라는 뜻이라 배너로 말해 준다.
 */
export function describeSocketStatus(status: SocketStatus, errorCode?: string): string | null {
    switch (status) {
        case "joined":
            return null
        case "connecting":
        case "open":
            return "연결하는 중입니다…"
        case "reconnecting":
            return "연결이 끊겼습니다. 다시 연결하는 중입니다…"
        case "rejected":
            // 서버가 거절 직전에 보내 준 ERROR 프레임의 code. 프레임이 오기 전에 연결이
            // 끊기면 없을 수 있다.
            return errorCode
                ? getFriendlyErrorMessage(errorCode)
                : "방에 입장할 수 없습니다. 링크를 다시 확인해 주세요."
        case "closed":
            return "서버와의 연결이 끊어졌습니다. 페이지를 새로고침해 주세요."
    }
}
