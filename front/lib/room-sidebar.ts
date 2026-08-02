import type { ParticipantView } from "@/lib/types"

/**
 * RoomSidebar 컴포넌트가 그리기만 하도록 뽑아낸 방 상태 판단과 클립보드 복사. game-join.ts /
 * game-hub.ts 와 같은 이유로 React 에 의존하지 않는다 — 여기서 값이 조용히 틀리면 몇 주를
 * 그대로 갈 종류라, 컴포넌트 밖에 두고 (Task 9 의 vitest 가 들어오면) 테스트로 고정한다.
 */

/**
 * app-webflux 의 `RoomService.MIN_PLAYERS`(2)와 같은 값이다. 여기서 값을 바꿔도 서버 판정은
 * 그대로이므로, 바꾸려면 RoomService 를 먼저 고친다.
 */
export const MIN_ROOM_PLAYERS_TO_START = 2

/** 소유자 표시(왕관 아이콘)와 "게임 시작" 버튼 노출 여부가 같이 쓰는 판단. */
export function isRoomHost(selfParticipantId: string | null, hostParticipantId: string): boolean {
    return selfParticipantId !== null && selfParticipantId === hostParticipantId
}

/**
 * `Room.beginGame(String, int)` 의 인원·준비 조건과 같다 — `members.size() >= minPlayers`
 * 그리고 `members.values().stream().allMatch(RoomMember::ready)`(방장 포함 전원). 정원 일치
 * (오목 2인 고정)나 방장 여부, 방 상태(WAITING)는 서버가 최종 판정하므로 여기서 다시 걸지
 * 않는다 — 이 함수가 참이어도 서버가 `NOT_ALL_READY`/`NOT_HOST` 등으로 거절할 수 있고, 그건
 * 정상이다. 이 값은 그 결과를 미리 보여주는 것뿐이지, 서버를 대신하는 것이 아니다. 느슨하게
 * (예: 전원 대신 정원만 채우면 된다고) 바꾸면 버튼은 활성화된 채 서버만 계속 거절하는 어긋난
 * 화면이 된다.
 */
export function canStartRoom(participants: ParticipantView[]): boolean {
    return (
        participants.length >= MIN_ROOM_PLAYERS_TO_START && participants.every((participant) => participant.ready)
    )
}

/**
 * 초대 링크 복사. post-detail.tsx 의 handleShare/fallbackCopy 와 같은 이유로 두 경로를 둔다 —
 * 비보안 컨텍스트(HTTP)나 Clipboard API 가 없는 브라우저에서 `navigator.clipboard.writeText`
 * 는 예외를 던지는데, 그걸 그대로 두면 클릭 핸들러 밖에서 unhandled rejection 이 나고 버튼은
 * 눌러도 아무 반응이 없다. 성공 여부를 boolean 으로 돌려주고, 복사 완료 표시(체크 아이콘)는
 * 호출부가 그 값을 보고 켠다.
 */
export async function copyTextToClipboard(text: string): Promise<boolean> {
    if (typeof window !== "undefined" && navigator.clipboard && window.isSecureContext) {
        try {
            await navigator.clipboard.writeText(text)
            return true
        } catch (error) {
            console.error("clipboard.writeText 실패:", error)
        }
    }
    return fallbackCopyToClipboard(text)
}

function fallbackCopyToClipboard(text: string): boolean {
    if (typeof document === "undefined") {
        return false
    }
    // select() 는 포커스를 가져간다. 복사 후 원래 있던 곳으로 돌려놓지 않으면 키보드로
    // 조작하던 사용자가 위치를 잃는다. position:fixed 라 문서 높이는 늘지 않으므로
    // 스크롤은 건드리지 않는다.
    const previouslyFocused = document.activeElement as HTMLElement | null
    const textarea = document.createElement("textarea")
    textarea.value = text
    textarea.style.position = "fixed"
    textarea.style.left = "-9999px"
    document.body.appendChild(textarea)
    textarea.select()
    try {
        return document.execCommand("copy")
    } catch (error) {
        console.error("execCommand copy 실패:", error)
        return false
    } finally {
        document.body.removeChild(textarea)
        previouslyFocused?.focus?.()
    }
}
