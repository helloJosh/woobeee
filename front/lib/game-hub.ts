import { gameAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
import { roomPath } from "@/lib/game-join"
import type { GameType } from "@/lib/types"

// 기존 호출부(app/game/page.tsx)가 계속 "@/lib/game-hub" 에서 가져다 쓸 수 있게 재수출한다.
export { assertNever } from "@/lib/game-errors"

/**
 * 게임 허브의 "방 만들기" 버튼을 눌렀을 때 벌어지는 일 — 인증 확인, 방 생성 API 호출,
 * 이동할 URL 구성, 실패 시 보여줄 메시지 — 을 컴포넌트 밖으로 뺀 로직.
 * 라우팅(useRouter)과 인증 상태(useAuth)는 페이지 컴포넌트가 주입하므로, 이 모듈은
 * React 에 의존하지 않고 gameAPI 만 모킹하면 테스트할 수 있다.
 */
export type CreateRoomOutcome =
    | { kind: "redirect-to-login" }
    | { kind: "navigate"; path: string }
    | { kind: "error"; message: string }

/**
 * URL 은 roomPath 하나로만 만든다. 예전에는 여기서 직접 조립했는데, 그러면 초대 링크의
 * 모양이 두 군데에 있게 된다 — 방을 만든 사람이 가는 URL 과, 로그인을 마친 초대 손님이
 * 돌아오는 URL(game-join.ts 의 roomPath). 세그먼트는 GAME_ROUTE_SEGMENTS 로 묶었지만
 * 인코딩이 남아 있었다: 여기서는 roomId·inviteCode 를 날것으로 끼워 넣고 roomPath 는
 * encodeURIComponent 를 씌웠다. 오늘은 둘 다 서버가 만든 값이라 결과가 같지만, 초대 코드에
 * `+`나 `&`나 `/`가 한 번 들어가는 순간 두 URL 이 갈라지고 그중 하나만 맞는다 — 방 만들기는
 * 되는데 로그인 후 복귀만 404 로 떨어지는, 재현하기 고약한 모양이 된다.
 */
export async function createGameRoom(
    gameType: GameType,
    isAuthenticated: boolean
): Promise<CreateRoomOutcome> {
    if (!isAuthenticated) {
        return { kind: "redirect-to-login" }
    }

    try {
        const room = await gameAPI.createRoom(gameType)
        return { kind: "navigate", path: roomPath(gameType, room.roomId, room.inviteCode) }
    } catch (error) {
        console.error("Failed to create room:", error)
        return { kind: "error", message: describeCreateRoomError(error) }
    }
}

function describeCreateRoomError(error: unknown): string {
    return describeGameApiError(error, "방을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.")
}
