import { gameAPI } from "@/lib/api"
import type { GameType } from "@/lib/types"

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

/**
 * apiRequest(front/lib/api.ts)는 파싱 가능한 4xx/401은 alert로 이미 안내하고, 친절한
 * 메시지가 담긴 Error를 던진다. 반면 백엔드 다운·오프라인·CORS 같은 네트워크 레벨 실패는
 * fetch가 던지는 TypeError("Failed to fetch" 등)가 그대로 올라오는데, 이 경로에는 alert도
 * 친절한 메시지도 없다 — 그래서 이 경우만 별도로 안내 문구를 채운다.
 */
function describeCreateRoomError(error: unknown): string {
    if (error instanceof TypeError) {
        return "서버에 연결할 수 없습니다. 네트워크 상태를 확인하고 다시 시도해 주세요."
    }
    if (error instanceof Error && error.message) {
        return error.message
    }
    return "방을 만들지 못했습니다. 잠시 후 다시 시도해 주세요."
}
