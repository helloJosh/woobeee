import { gameAPI } from "@/lib/api"
import { describeGameApiError } from "@/lib/game-errors"
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

export async function createGameRoom(
    gameType: GameType,
    path: string,
    isAuthenticated: boolean
): Promise<CreateRoomOutcome> {
    if (!isAuthenticated) {
        return { kind: "redirect-to-login" }
    }

    try {
        const room = await gameAPI.createRoom(gameType)
        return { kind: "navigate", path: `/game/${path}/${room.roomId}?invite=${room.inviteCode}` }
    } catch (error) {
        console.error("Failed to create room:", error)
        return { kind: "error", message: describeCreateRoomError(error) }
    }
}

function describeCreateRoomError(error: unknown): string {
    return describeGameApiError(error, "방을 만들지 못했습니다. 잠시 후 다시 시도해 주세요.")
}
